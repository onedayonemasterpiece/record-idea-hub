from __future__ import annotations

from pathlib import Path

from record_idea_hub.database import Database


def test_database_tracks_chunk_progress_and_idempotency(tmp_path: Path) -> None:
    db = Database(tmp_path / "state.sqlite3")
    db.initialize()
    db.create_session(
        session_id="voice-20260827-dbtest01",
        started_at="2026-08-27T12:00:00-04:00",
        timezone="America/Indiana/Indianapolis",
        device_label="Samsung S21 Ultra",
    )
    row, duplicate = db.put_chunk(
        session_id="voice-20260827-dbtest01",
        chunk_index=0,
        start_ms=0,
        end_ms=60000,
        sha256="a" * 64,
        local_path=str(tmp_path / "0.wav"),
    )
    assert not duplicate
    _, duplicate = db.put_chunk(
        session_id="voice-20260827-dbtest01",
        chunk_index=0,
        start_ms=0,
        end_ms=60000,
        sha256="a" * 64,
        local_path=str(tmp_path / "0.wav"),
    )
    assert duplicate
    claimed = db.claim_chunk()
    assert claimed is not None
    db.complete_chunk(
        "voice-20260827-dbtest01",
        0,
        {"transcript": "Текст", "language": "ru", "uncertain_fragments": []},
    )
    db.finish_session(
        session_id="voice-20260827-dbtest01",
        ended_at="2026-08-27T12:01:00-04:00",
        duration_ms=60000,
        expected_chunks=1,
    )
    progress = db.progress("voice-20260827-dbtest01")
    assert progress is not None
    assert progress["upload_progress"] == 1
    assert progress["transcription_progress"] == 1
    assert db.claim_finalizable_session() is not None


def test_database_exposes_quota_and_retry_states(tmp_path: Path) -> None:
    db = Database(tmp_path / "state.sqlite3")
    db.initialize()
    session_id = "voice-20260827-retry001"
    db.create_session(
        session_id=session_id,
        started_at="2026-08-27T12:00:00-04:00",
        timezone="America/Indiana/Indianapolis",
        device_label="Samsung S21 Ultra",
    )
    db.put_chunk(
        session_id=session_id,
        chunk_index=0,
        start_ms=0,
        end_ms=1000,
        sha256="b" * 64,
        local_path=str(tmp_path / "0.wav"),
    )
    claimed = db.claim_chunk()
    assert claimed is not None
    db.retry_chunk(
        session_id,
        0,
        error="shared limiter: rpm",
        retry_after_seconds=60,
        quota=True,
    )
    progress = db.progress(session_id)
    assert progress is not None
    assert progress["state"] == "waiting_for_quota"
    assert progress["retry_after_seconds"] is not None


def test_verified_audio_paths_survive_restart_for_cleanup(tmp_path: Path) -> None:
    db = Database(tmp_path / "state.sqlite3")
    db.initialize()
    session_id = "voice-20260827-clean001"
    audio = tmp_path / "0.wav"
    audio.write_bytes(b"audio")
    db.create_session(
        session_id=session_id,
        started_at="2026-08-27T12:00:00-04:00",
        timezone="America/Indiana/Indianapolis",
        device_label="Samsung S21 Ultra",
    )
    db.put_chunk(
        session_id=session_id,
        chunk_index=0,
        start_ms=0,
        end_ms=1000,
        sha256="c" * 64,
        local_path=str(audio),
    )
    db.mark_published(
        session_id,
        detail_path="registry/sessions/2026/08/example.md",
        commit_sha="d" * 40,
        github_url="https://github.com/example/repo/blob/d/example.md",
    )
    restarted = Database(db.path)
    restarted.initialize()
    assert restarted.verified_audio_paths() == [str(audio)]
