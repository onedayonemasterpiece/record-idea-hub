from __future__ import annotations

import json
import sqlite3
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.RLock()

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA foreign_keys=ON;

                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    started_at TEXT NOT NULL,
                    ended_at TEXT,
                    timezone TEXT NOT NULL,
                    device_label TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    expected_chunks INTEGER,
                    finished INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL DEFAULT 'receiving',
                    summary_json TEXT,
                    detail_path TEXT,
                    github_commit_sha TEXT,
                    github_url TEXT,
                    last_error TEXT,
                    retry_at REAL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );

                CREATE TABLE IF NOT EXISTS chunks (
                    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
                    chunk_index INTEGER NOT NULL,
                    start_ms INTEGER NOT NULL,
                    end_ms INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'uploaded',
                    transcript_json TEXT,
                    last_error TEXT,
                    retry_at REAL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (session_id, chunk_index)
                );

                CREATE INDEX IF NOT EXISTS idx_chunks_work
                    ON chunks(state, retry_at, session_id, chunk_index);
                CREATE INDEX IF NOT EXISTS idx_sessions_work
                    ON sessions(state, retry_at, finished);
                """
            )
            # A process restart must make claimed-but-unfinished work visible again.
            connection.execute(
                "UPDATE chunks SET state='uploaded' WHERE state='transcribing'"
            )
            connection.execute(
                "UPDATE sessions SET state='processing' "
                "WHERE state IN ('summarizing', 'publishing')"
            )

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            connection = sqlite3.connect(self.path, timeout=30)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA foreign_keys=ON")
            try:
                yield connection
                connection.commit()
            except Exception:
                connection.rollback()
                raise
            finally:
                connection.close()

    @staticmethod
    def _row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        return dict(row) if row is not None else None

    def create_session(
        self,
        *,
        session_id: str,
        started_at: str,
        timezone: str,
        device_label: str,
    ) -> dict[str, Any]:
        now = time.time()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO sessions (
                    session_id, started_at, timezone, device_label, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO NOTHING
                """,
                (session_id, started_at, timezone, device_label, now, now),
            )
            row = connection.execute(
                "SELECT * FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
        assert row is not None
        existing = dict(row)
        if (
            existing["started_at"] != started_at
            or existing["timezone"] != timezone
            or existing["device_label"] != device_label
        ):
            raise ValueError("session_id already exists with different metadata")
        return existing

    def get_session(self, session_id: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
        return self._row(row)

    def put_chunk(
        self,
        *,
        session_id: str,
        chunk_index: int,
        start_ms: int,
        end_ms: int,
        sha256: str,
        local_path: str,
    ) -> tuple[dict[str, Any], bool]:
        with self._connect() as connection:
            existing = connection.execute(
                "SELECT * FROM chunks WHERE session_id=? AND chunk_index=?",
                (session_id, chunk_index),
            ).fetchone()
            if existing is not None:
                row = dict(existing)
                if row["sha256"] != sha256:
                    raise ValueError("chunk index already exists with a different SHA-256")
                return row, True
            connection.execute(
                """
                INSERT INTO chunks (
                    session_id, chunk_index, start_ms, end_ms, sha256, local_path, state
                ) VALUES (?, ?, ?, ?, ?, ?, 'uploaded')
                """,
                (session_id, chunk_index, start_ms, end_ms, sha256, local_path),
            )
            connection.execute(
                "UPDATE sessions SET updated_at=?, last_error=NULL WHERE session_id=?",
                (time.time(), session_id),
            )
            row = connection.execute(
                "SELECT * FROM chunks WHERE session_id=? AND chunk_index=?",
                (session_id, chunk_index),
            ).fetchone()
        assert row is not None
        return dict(row), False

    def finish_session(
        self,
        *,
        session_id: str,
        ended_at: str,
        duration_ms: int,
        expected_chunks: int,
    ) -> None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
            if row is None:
                raise KeyError(session_id)
            if row["state"] == "published_verified":
                return
            connection.execute(
                """
                UPDATE sessions
                SET ended_at=?, duration_ms=?, expected_chunks=?, finished=1,
                    state=CASE WHEN state='receiving' THEN 'processing' ELSE state END,
                    updated_at=?, last_error=NULL
                WHERE session_id=?
                """,
                (ended_at, duration_ms, expected_chunks, time.time(), session_id),
            )

    def list_chunks(self, session_id: str) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM chunks WHERE session_id=? ORDER BY chunk_index",
                (session_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def claim_chunk(self) -> dict[str, Any] | None:
        now = time.time()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT * FROM chunks
                WHERE state IN ('uploaded', 'waiting_for_quota', 'retryable_error')
                  AND (retry_at IS NULL OR retry_at <= ?)
                ORDER BY session_id, chunk_index
                LIMIT 1
                """,
                (now,),
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                """
                UPDATE chunks
                SET state='transcribing', attempts=attempts+1, last_error=NULL
                WHERE session_id=? AND chunk_index=?
                """,
                (row["session_id"], row["chunk_index"]),
            )
            claimed = connection.execute(
                "SELECT * FROM chunks WHERE session_id=? AND chunk_index=?",
                (row["session_id"], row["chunk_index"]),
            ).fetchone()
        return self._row(claimed)

    def complete_chunk(self, session_id: str, chunk_index: int, transcript: dict[str, Any]) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE chunks
                SET state='transcribed', transcript_json=?, retry_at=NULL, last_error=NULL
                WHERE session_id=? AND chunk_index=?
                """,
                (json.dumps(transcript, ensure_ascii=False), session_id, chunk_index),
            )
            connection.execute(
                """
                UPDATE sessions
                SET updated_at=?, retry_at=NULL, last_error=NULL
                WHERE session_id=?
                """,
                (time.time(), session_id),
            )

    def retry_chunk(
        self,
        session_id: str,
        chunk_index: int,
        *,
        error: str,
        retry_after_seconds: int,
        quota: bool = False,
    ) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE chunks
                SET state=?, retry_at=?, last_error=?
                WHERE session_id=? AND chunk_index=?
                """,
                (
                    "waiting_for_quota" if quota else "retryable_error",
                    time.time() + max(1, retry_after_seconds),
                    error[:1000],
                    session_id,
                    chunk_index,
                ),
            )
            connection.execute(
                """
                UPDATE sessions
                SET retry_at=?, last_error=?, updated_at=?
                WHERE session_id=? AND state!='published_verified'
                """,
                (
                    time.time() + max(1, retry_after_seconds),
                    error[:1000],
                    time.time(),
                    session_id,
                ),
            )

    def claim_finalizable_session(self) -> dict[str, Any] | None:
        now = time.time()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT s.*
                FROM sessions s
                WHERE s.finished=1
                  AND s.expected_chunks IS NOT NULL
                  AND s.state IN ('processing', 'waiting_for_quota', 'retryable_error')
                  AND (s.retry_at IS NULL OR s.retry_at <= ?)
                  AND (SELECT COUNT(*) FROM chunks c WHERE c.session_id=s.session_id)
                      = s.expected_chunks
                  AND (SELECT COUNT(*) FROM chunks c
                       WHERE c.session_id=s.session_id AND c.state='transcribed')
                      = s.expected_chunks
                ORDER BY s.created_at
                LIMIT 1
                """,
                (now,),
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                """
                UPDATE sessions
                SET state='summarizing', attempts=attempts+1, last_error=NULL, updated_at=?
                WHERE session_id=?
                """,
                (now, row["session_id"]),
            )
            claimed = connection.execute(
                "SELECT * FROM sessions WHERE session_id=?", (row["session_id"],)
            ).fetchone()
        return self._row(claimed)

    def set_publishing(self, session_id: str, summary: dict[str, Any]) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE sessions
                SET state='publishing', summary_json=?, retry_at=NULL,
                    last_error=NULL, updated_at=?
                WHERE session_id=?
                """,
                (json.dumps(summary, ensure_ascii=False), time.time(), session_id),
            )

    def retry_session(
        self,
        session_id: str,
        *,
        error: str,
        retry_after_seconds: int,
        quota: bool = False,
    ) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE sessions
                SET state=?, retry_at=?, last_error=?, updated_at=?
                WHERE session_id=? AND state!='published_verified'
                """,
                (
                    "waiting_for_quota" if quota else "retryable_error",
                    time.time() + max(1, retry_after_seconds),
                    error[:1000],
                    time.time(),
                    session_id,
                ),
            )

    def mark_published(
        self,
        session_id: str,
        *,
        detail_path: str,
        commit_sha: str,
        github_url: str,
    ) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE sessions
                SET state='published_verified', detail_path=?, github_commit_sha=?,
                    github_url=?, retry_at=NULL, last_error=NULL, updated_at=?
                WHERE session_id=?
                """,
                (detail_path, commit_sha, github_url, time.time(), session_id),
            )

    def verified_audio_paths(self) -> list[str]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT c.local_path
                FROM chunks c
                JOIN sessions s ON s.session_id=c.session_id
                WHERE s.state='published_verified'
                """
            ).fetchall()
        return [str(row["local_path"]) for row in rows]

    def progress(self, session_id: str) -> dict[str, Any] | None:
        session = self.get_session(session_id)
        if session is None:
            return None
        chunks = self.list_chunks(session_id)
        expected = session["expected_chunks"]
        uploaded = len(chunks)
        transcribed = sum(1 for chunk in chunks if chunk["state"] == "transcribed")
        denominator = expected or max(uploaded, 1)
        now = time.time()
        retry_at = session.get("retry_at")
        chunk_states = {str(chunk["state"]) for chunk in chunks}
        effective_state = str(session["state"])
        if effective_state != "published_verified":
            if "waiting_for_quota" in chunk_states:
                effective_state = "waiting_for_quota"
            elif "retryable_error" in chunk_states:
                effective_state = "retryable_error"
            elif effective_state in {"summarizing", "publishing"}:
                effective_state = "publishing"
            elif bool(session["finished"]):
                effective_state = "processing"
            else:
                effective_state = "receiving"
        return {
            **session,
            "state": effective_state,
            "chunks_uploaded": uploaded,
            "chunks_transcribed": transcribed,
            "upload_progress": min(1.0, uploaded / denominator),
            "transcription_progress": min(1.0, transcribed / denominator),
            "retry_after_seconds": (
                max(1, int(float(retry_at) - now))
                if retry_at is not None and float(retry_at) > now
                else None
            ),
        }
