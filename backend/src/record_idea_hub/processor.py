from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Any

from .database import Database
from .errors import QuotaDeferred
from .gemini_lite import GeminiLiteService
from .github_writer import IdeaHubGitHubWriter
from .models import SummaryPayload

logger = logging.getLogger(__name__)


class Processor:
    def __init__(
        self,
        *,
        database: Database,
        gemini: GeminiLiteService,
        github: IdeaHubGitHubWriter,
        poll_seconds: float = 2.0,
    ) -> None:
        self.database = database
        self.gemini = gemini
        self.github = github
        self.poll_seconds = max(0.2, poll_seconds)
        self._wake = asyncio.Event()
        self._stop = asyncio.Event()

    def notify(self) -> None:
        self._wake.set()

    def cleanup_verified_audio(self) -> None:
        for path_value in self.database.verified_audio_paths():
            path = Path(path_value)
            try:
                path.unlink(missing_ok=True)
                parent = path.parent
                if parent.is_dir() and not any(parent.iterdir()):
                    parent.rmdir()
            except OSError:
                logger.warning("failed to clean verified audio chunk %s", path)

    async def stop(self) -> None:
        self._stop.set()
        self._wake.set()

    async def run(self) -> None:
        while not self._stop.is_set():
            try:
                worked = await self.process_once()
            except Exception:
                logger.exception("processor iteration failed")
                worked = False
            if worked:
                continue
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=self.poll_seconds)
            except TimeoutError:
                pass

    async def process_once(self) -> bool:
        chunk = self.database.claim_chunk()
        if chunk is not None:
            await self._transcribe_chunk(chunk)
            return True
        session = self.database.claim_finalizable_session()
        if session is not None:
            await self._finish_session(session)
            return True
        return False

    async def _transcribe_chunk(self, chunk: dict[str, Any]) -> None:
        session_id = str(chunk["session_id"])
        index = int(chunk["chunk_index"])
        try:
            payload = await self.gemini.transcribe(
                Path(chunk["local_path"]),
                duration_ms=int(chunk["end_ms"]) - int(chunk["start_ms"]),
            )
            self.database.complete_chunk(
                session_id,
                index,
                payload.model_dump(),
            )
        except QuotaDeferred as exc:
            self.database.retry_chunk(
                session_id,
                index,
                error=f"shared limiter: {exc.reason}",
                retry_after_seconds=exc.retry_after_seconds,
                quota=True,
            )
        except Exception as exc:
            delay = min(300, 5 * (2 ** min(int(chunk.get("attempts") or 1), 6)))
            self.database.retry_chunk(
                session_id,
                index,
                error=f"transcription: {type(exc).__name__}: {exc}",
                retry_after_seconds=delay,
                quota=False,
            )

    @staticmethod
    def _combined_transcript(chunks: list[dict[str, Any]]) -> str:
        sections: list[str] = []
        for chunk in chunks:
            payload = json.loads(chunk["transcript_json"])
            start = int(chunk["start_ms"]) // 1000
            end = int(chunk["end_ms"]) // 1000
            sections.append(
                f"[chunk {chunk['chunk_index']}; {start}s–{end}s]\n"
                f"{str(payload['transcript']).strip()}"
            )
        return "\n\n".join(sections)

    async def _finish_session(self, session: dict[str, Any]) -> None:
        session_id = str(session["session_id"])
        try:
            chunks = self.database.list_chunks(session_id)
            if session.get("summary_json"):
                summary = SummaryPayload.model_validate_json(session["summary_json"])
            else:
                summary = await self.gemini.summarize(self._combined_transcript(chunks))
            self.database.set_publishing(session_id, summary.model_dump())
            # Refresh after set_publishing so the persisted summary and timestamps
            # are the same data seen by retry/reconciliation paths.
            persisted = self.database.get_session(session_id)
            assert persisted is not None
            receipt = await self.github.publish(
                session=persisted,
                chunks=chunks,
                summary=summary,
                model=self.gemini.model,
            )
            self.database.mark_published(
                session_id,
                detail_path=receipt.detail_path,
                commit_sha=receipt.commit_sha,
                github_url=receipt.github_url,
            )
            # Server-side audio is dispensable only after exact-commit readback.
            self.cleanup_verified_audio()
        except QuotaDeferred as exc:
            self.database.retry_session(
                session_id,
                error=f"shared limiter: {exc.reason}",
                retry_after_seconds=exc.retry_after_seconds,
                quota=True,
            )
        except Exception as exc:
            delay = min(300, 10 * (2 ** min(int(session.get("attempts") or 1), 5)))
            self.database.retry_session(
                session_id,
                error=f"finalization: {type(exc).__name__}: {exc}",
                retry_after_seconds=delay,
            )
