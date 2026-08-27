from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx
import yaml
from jsonschema import Draft202012Validator, FormatChecker

from .errors import GitHubConflict
from .markdown import (
    build_registry_entry,
    detail_path_for,
    insert_registry_entry,
    render_source_packet,
)
from .models import SummaryPayload


@dataclass(frozen=True, slots=True)
class PublishReceipt:
    detail_path: str
    commit_sha: str
    github_url: str


class IdeaHubGitHubWriter:
    REGISTRY_PATH = "registry/intake-sessions.yaml"
    REGISTRY_SCHEMA_PATH = "schemas/intake-session.schema.json"

    @staticmethod
    def validate_registry_document(registry_text: str, schema_text: str) -> None:
        registry = yaml.safe_load(registry_text)
        schema = json.loads(schema_text)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(registry), key=lambda item: list(item.path))
        if errors:
            first = errors[0]
            location = ".".join(str(part) for part in first.absolute_path) or "<root>"
            raise GitHubConflict(
                f"generated intake registry fails current idea-hub schema at {location}: "
                f"{first.message}"
            )

    def __init__(
        self,
        *,
        repository: str,
        branch: str,
        token: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.repository = repository
        self.branch = branch
        self._owns_client = client is None
        self.client = client or httpx.AsyncClient(
            base_url="https://api.github.com",
            timeout=httpx.Timeout(30.0),
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "record-idea-hub/0.1",
            },
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self.client.aclose()

    async def _json(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        response = await self.client.request(method, path, **kwargs)
        response.raise_for_status()
        value = response.json()
        if not isinstance(value, dict):
            raise ValueError(f"GitHub returned a non-object for {path}")
        return value

    async def _head(self) -> tuple[str, str]:
        ref = await self._json(
            "GET", f"/repos/{self.repository}/git/ref/heads/{self.branch}"
        )
        head_sha = str(ref["object"]["sha"])
        commit = await self._json(
            "GET", f"/repos/{self.repository}/git/commits/{head_sha}"
        )
        return head_sha, str(commit["tree"]["sha"])

    async def _content(self, path: str, ref: str) -> tuple[str, str]:
        value = await self._json(
            "GET",
            f"/repos/{self.repository}/contents/{path}",
            params={"ref": ref},
        )
        encoded = str(value.get("content") or "").replace("\n", "")
        return base64.b64decode(encoded).decode("utf-8"), str(value["sha"])

    async def _blob(self, content: str) -> str:
        value = await self._json(
            "POST",
            f"/repos/{self.repository}/git/blobs",
            json={"content": content, "encoding": "utf-8"},
        )
        return str(value["sha"])

    async def publish(
        self,
        *,
        session: dict[str, Any],
        chunks: list[dict[str, Any]],
        summary: SummaryPayload,
        model: str,
    ) -> PublishReceipt:
        detail_path = detail_path_for(session["session_id"], session["started_at"])
        registered_at = datetime.fromtimestamp(
            float(session["created_at"]), tz=UTC
        ).isoformat().replace("+00:00", "Z")
        detail = render_source_packet(
            session=session,
            chunks=chunks,
            summary=summary,
            model=model,
            registered_at=registered_at,
        )
        entry = build_registry_entry(
            session=session,
            summary=summary,
            detail_path=detail_path,
            registered_at=registered_at,
        )

        for _attempt in range(4):
            head_sha, tree_sha = await self._head()
            registry, _registry_blob_sha = await self._content(
                self.REGISTRY_PATH, head_sha
            )
            registry_schema, _schema_blob_sha = await self._content(
                self.REGISTRY_SCHEMA_PATH, head_sha
            )
            if f"session_id: {session['session_id']}" in registry:
                # Idempotent replay after an unknown HTTP result.
                existing, _ = await self._content(detail_path, head_sha)
                if hashlib.sha256(existing.encode()).digest() != hashlib.sha256(
                    detail.encode()
                ).digest():
                    raise GitHubConflict(
                        "registry entry exists but the deterministic detail content differs"
                    )
                return PublishReceipt(
                    detail_path=detail_path,
                    commit_sha=head_sha,
                    github_url=(
                        f"https://github.com/{self.repository}/blob/"
                        f"{head_sha}/{detail_path}"
                    ),
                )

            updated_registry = insert_registry_entry(
                registry,
                entry=entry,
                updated_at=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            )
            self.validate_registry_document(updated_registry, registry_schema)
            detail_blob, registry_blob = await self._blob(detail), await self._blob(
                updated_registry
            )
            tree = await self._json(
                "POST",
                f"/repos/{self.repository}/git/trees",
                json={
                    "base_tree": tree_sha,
                    "tree": [
                        {
                            "path": detail_path,
                            "mode": "100644",
                            "type": "blob",
                            "sha": detail_blob,
                        },
                        {
                            "path": self.REGISTRY_PATH,
                            "mode": "100644",
                            "type": "blob",
                            "sha": registry_blob,
                        },
                    ],
                },
            )
            commit = await self._json(
                "POST",
                f"/repos/{self.repository}/git/commits",
                json={
                    "message": f"intake: register voice session {session['session_id']}",
                    "tree": tree["sha"],
                    "parents": [head_sha],
                },
            )
            commit_sha = str(commit["sha"])
            response = await self.client.patch(
                f"/repos/{self.repository}/git/refs/heads/{self.branch}",
                json={"sha": commit_sha, "force": False},
            )
            if response.status_code in {409, 422}:
                continue
            response.raise_for_status()

            # The client receives success only after both files are read back at
            # the exact commit and their content is verified.
            read_detail, _ = await self._content(detail_path, commit_sha)
            read_registry, _ = await self._content(self.REGISTRY_PATH, commit_sha)
            if hashlib.sha256(read_detail.encode()).digest() != hashlib.sha256(
                detail.encode()
            ).digest():
                raise GitHubConflict("detail readback hash mismatch")
            if f"session_id: {session['session_id']}" not in read_registry:
                raise GitHubConflict("registry readback does not contain the session")
            return PublishReceipt(
                detail_path=detail_path,
                commit_sha=commit_sha,
                github_url=(
                    f"https://github.com/{self.repository}/blob/"
                    f"{commit_sha}/{detail_path}"
                ),
            )
        raise GitHubConflict("idea-hub main moved repeatedly; retry later")
