from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator


class SessionCreate(BaseModel):
    session_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{7,127}$")
    started_at: str
    timezone: str = Field(min_length=1, max_length=80)
    device_label: str = Field(default="android", min_length=1, max_length=80)


class SessionComplete(BaseModel):
    ended_at: str
    duration_ms: int = Field(ge=1)
    chunk_count: int = Field(ge=1, le=10_000)


class ChunkReceipt(BaseModel):
    session_id: str
    chunk_index: int
    sha256: str
    accepted: bool
    duplicate: bool = False


class SessionProgress(BaseModel):
    session_id: str
    state: str
    recording_finished: bool
    chunks_expected: int | None
    chunks_uploaded: int
    chunks_transcribed: int
    upload_progress: float = Field(ge=0, le=1)
    transcription_progress: float = Field(ge=0, le=1)
    summary_ready: bool
    github_verified: bool
    github_url: str | None = None
    github_commit_sha: str | None = None
    last_error: str | None = None
    retry_after_seconds: int | None = None


class TranscriptPayload(BaseModel):
    transcript: str = Field(min_length=1)
    language: str = "ru"
    uncertain_fragments: list[str] = Field(default_factory=list)


class SummaryPayload(BaseModel):
    title: str = Field(min_length=1, max_length=180)
    short_summary: str = Field(min_length=1)
    detailed_summary: str = Field(min_length=1)
    theses: list[str] = Field(default_factory=list)
    ideas: list[str] = Field(default_factory=list)
    decisions: list[str] = Field(default_factory=list)
    tasks: list[str] = Field(default_factory=list)
    facts: list[str] = Field(default_factory=list)
    entities: list[str] = Field(default_factory=list)
    related_projects: list[str] = Field(default_factory=list)
    open_questions: list[str] = Field(default_factory=list)
    contradictions: list[str] = Field(default_factory=list)
    uncertain_fragments: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)

    @field_validator(
        "theses",
        "ideas",
        "decisions",
        "tasks",
        "facts",
        "entities",
        "related_projects",
        "open_questions",
        "contradictions",
        "uncertain_fragments",
        "tags",
        mode="before",
    )
    @classmethod
    def normalize_lists(cls, value: object) -> list[str]:
        if value is None:
            return []
        if isinstance(value, str):
            return [value.strip()] if value.strip() else []
        if not isinstance(value, list):
            return []
        out: list[str] = []
        for item in value:
            text = str(item).strip()
            if text and text not in out:
                out.append(text)
        return out


SessionState = Literal[
    "receiving",
    "processing",
    "waiting_for_quota",
    "publishing",
    "published_verified",
    "retryable_error",
]
