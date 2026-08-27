from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from .errors import ConfigurationError


@dataclass(frozen=True, slots=True)
class Settings:
    data_dir: Path
    device_token: str
    gemini_model: str = "gemini-3.1-flash-lite"
    github_repository: str = "onedayonemasterpiece/idea-hub"
    github_branch: str = "main"
    github_token: str = ""
    max_chunk_bytes: int = 16 * 1024 * 1024
    processor_poll_seconds: float = 2.0
    processor_enabled: bool = True
    events_bot_ref: str = "8710e56fa3685f6c30a90cd062d532dce0348cce"

    @classmethod
    def from_env(cls) -> Settings:
        model = (os.getenv("GEMINI_LITE_MODEL") or "gemini-3.1-flash-lite").strip()
        if "flash-lite" not in model.lower():
            raise ConfigurationError(
                "GEMINI_LITE_MODEL must be a Gemini Flash-Lite model; "
                f"received {model!r}"
            )
        device_token = (os.getenv("RECORD_IDEA_HUB_DEVICE_TOKEN") or "").strip()
        if len(device_token) < 24:
            raise ConfigurationError(
                "RECORD_IDEA_HUB_DEVICE_TOKEN must be configured with at least 24 characters"
            )
        github_token = (os.getenv("IDEA_HUB_GITHUB_TOKEN") or "").strip()
        if not github_token:
            raise ConfigurationError("IDEA_HUB_GITHUB_TOKEN is required")
        repository = (os.getenv("IDEA_HUB_GITHUB_REPOSITORY") or cls.github_repository).strip()
        if "/" not in repository:
            raise ConfigurationError("IDEA_HUB_GITHUB_REPOSITORY must be owner/repository")
        branch = (os.getenv("IDEA_HUB_GITHUB_BRANCH") or "main").strip() or "main"
        data_dir = Path(os.getenv("RECORD_IDEA_HUB_DATA_DIR") or "/data").expanduser()
        max_mb = int(os.getenv("RECORD_IDEA_HUB_MAX_CHUNK_MB") or "16")
        return cls(
            data_dir=data_dir,
            device_token=device_token,
            gemini_model=model,
            github_repository=repository,
            github_branch=branch,
            github_token=github_token,
            max_chunk_bytes=max(1, max_mb) * 1024 * 1024,
            processor_poll_seconds=float(
                os.getenv("RECORD_IDEA_HUB_PROCESSOR_POLL_SECONDS") or "2"
            ),
            processor_enabled=(
                os.getenv("RECORD_IDEA_HUB_PROCESSOR_ENABLED", "1").strip().lower()
                in {"1", "true", "yes", "on"}
            ),
            events_bot_ref=(
                os.getenv("EVENTS_BOT_GOOGLE_AI_REF")
                or "8710e56fa3685f6c30a90cd062d532dce0348cce"
            ).strip(),
        )

    @property
    def database_path(self) -> Path:
        return self.data_dir / "record-idea-hub.sqlite3"

    @property
    def audio_dir(self) -> Path:
        return self.data_dir / "audio"
