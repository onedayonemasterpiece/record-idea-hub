from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from record_idea_hub.database import Database
from record_idea_hub.github_writer import PublishReceipt
from record_idea_hub.models import SummaryPayload, TranscriptPayload
from record_idea_hub.processor import Processor


class FakeGemini:
    model = "gemini-3.1-flash-lite"

    async def transcribe(self, _path: Path, *, duration_ms: int) -> TranscriptPayload:
        assert duration_ms == 1000
        return TranscriptPayload(
            transcript="Нужно сохранить эту идею.",
            language="ru",
            uncertain_fragments=[],
        )

    async def summarize(self, transcript: str) -> SummaryPayload:
        assert "Нужно сохранить" in transcript
        return SummaryPayload(
            title="Тестовая голосовая идея",
            short_summary="Коротко.",
            detailed_summary="Подробно.",
            ideas=["Сохранить идею"],
        )


class FakeGitHub:
    async def publish(self, **kwargs) -> PublishReceipt:
        assert kwargs["session"]["session_id"] == "voice-20260827-proc001"
        return PublishReceipt(
            detail_path="registry/sessions/2026/08/voice-20260827-proc001.md",
            commit_sha="a" * 40,
            github_url="https://github.com/example/repo/blob/a/path",
        )

    async def aclose(self) -> None:
        return None


@pytest.mark.asyncio
async def test_processor_reaches_verified_and_deletes_audio(tmp_path: Path) -> None:
    db = Database(tmp_path / "state.sqlite3")
    db.initialize()
    session_id = "voice-20260827-proc001"
    db.create_session(
        session_id=session_id,
        started_at="2026-08-27T12:00:00-04:00",
        timezone="America/Indiana/Indianapolis",
        device_label="Samsung S21 Ultra",
    )
    audio = tmp_path / "0.wav"
    audio.write_bytes(b"RIFFtest")
    db.put_chunk(
        session_id=session_id,
        chunk_index=0,
        start_ms=0,
        end_ms=1000,
        sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
        local_path=str(audio),
    )
    db.finish_session(
        session_id=session_id,
        ended_at="2026-08-27T12:00:01-04:00",
        duration_ms=1000,
        expected_chunks=1,
    )
    processor = Processor(database=db, gemini=FakeGemini(), github=FakeGitHub())
    assert await processor.process_once() is True
    assert await processor.process_once() is True
    state = db.get_session(session_id)
    assert state is not None
    assert state["state"] == "published_verified"
    assert state["github_commit_sha"] == "a" * 40
    assert not audio.exists()
