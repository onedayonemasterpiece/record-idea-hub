from __future__ import annotations

import hashlib
from pathlib import Path

from fastapi.testclient import TestClient

from record_idea_hub.config import Settings
from record_idea_hub.main import create_app


class FakeGemini:
    model = "gemini-3.1-flash-lite"


class FakeGitHub:
    async def aclose(self) -> None:
        return None


def test_api_accepts_idempotent_chunk_and_reports_progress(tmp_path: Path) -> None:
    settings = Settings(
        data_dir=tmp_path,
        device_token="x" * 32,
        github_token="test",
        processor_enabled=False,
    )
    app = create_app(settings, gemini=FakeGemini(), github=FakeGitHub())
    audio = b"RIFF" + b"0" * 128
    digest = hashlib.sha256(audio).hexdigest()
    headers = {"Authorization": f"Bearer {'x' * 32}"}
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            headers=headers,
            json={
                "session_id": "voice-20260827-apitest1",
                "started_at": "2026-08-27T12:00:00-04:00",
                "timezone": "America/Indiana/Indianapolis",
                "device_label": "Samsung S21 Ultra",
            },
        )
        assert response.status_code == 200
        upload_headers = {
            **headers,
            "X-Chunk-SHA256": digest,
            "X-Chunk-Start-Ms": "0",
            "X-Chunk-End-Ms": "1000",
            "Content-Type": "audio/wav",
        }
        first = client.put(
            "/v1/sessions/voice-20260827-apitest1/chunks/0",
            headers=upload_headers,
            content=audio,
        )
        second = client.put(
            "/v1/sessions/voice-20260827-apitest1/chunks/0",
            headers=upload_headers,
            content=audio,
        )
        assert first.status_code == 200
        assert second.json()["duplicate"] is True
        completed = client.post(
            "/v1/sessions/voice-20260827-apitest1/complete",
            headers=headers,
            json={
                "ended_at": "2026-08-27T12:00:01-04:00",
                "duration_ms": 1000,
                "chunk_count": 1,
            },
        )
        assert completed.status_code == 200
        assert completed.json()["recording_finished"] is True
        assert completed.json()["chunks_uploaded"] == 1


def test_api_rejects_bad_token(tmp_path: Path) -> None:
    settings = Settings(
        data_dir=tmp_path,
        device_token="x" * 32,
        github_token="test",
        processor_enabled=False,
    )
    app = create_app(settings, gemini=FakeGemini(), github=FakeGitHub())
    with TestClient(app) as client:
        response = client.post(
            "/v1/sessions",
            headers={"Authorization": "Bearer wrong"},
            json={
                "session_id": "voice-20260827-apitest2",
                "started_at": "2026-08-27T12:00:00-04:00",
                "timezone": "UTC",
                "device_label": "phone",
            },
        )
        assert response.status_code == 401
