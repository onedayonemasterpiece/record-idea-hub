from __future__ import annotations

import hashlib
import os

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status

from .config import Settings
from .database import Database
from .models import (
    ChunkReceipt,
    SessionComplete,
    SessionCreate,
    SessionProgress,
)
from .processor import Processor
from .security import DeviceAuthenticator


def build_router(
    *,
    settings: Settings,
    database: Database,
    processor: Processor,
    authenticator: DeviceAuthenticator,
) -> APIRouter:
    router = APIRouter()

    @router.get("/healthz")
    def health() -> dict[str, str]:
        return {"status": "ok", "model": settings.gemini_model}

    @router.post(
        "/v1/sessions",
        dependencies=[Depends(authenticator.verify)],
    )
    def create_session(payload: SessionCreate) -> SessionProgress:
        try:
            database.create_session(
                session_id=payload.session_id,
                started_at=payload.started_at,
                timezone=payload.timezone,
                device_label=payload.device_label,
            )
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        processor.notify()
        return _progress(database, payload.session_id)

    @router.put(
        "/v1/sessions/{session_id}/chunks/{chunk_index}",
        response_model=ChunkReceipt,
        dependencies=[Depends(authenticator.verify)],
    )
    async def upload_chunk(
        session_id: str,
        chunk_index: int,
        request: Request,
        x_chunk_sha256: str = Header(alias="X-Chunk-SHA256"),
        x_chunk_start_ms: int = Header(alias="X-Chunk-Start-Ms"),
        x_chunk_end_ms: int = Header(alias="X-Chunk-End-Ms"),
    ) -> ChunkReceipt:
        if chunk_index < 0 or x_chunk_start_ms < 0 or x_chunk_end_ms <= x_chunk_start_ms:
            raise HTTPException(status_code=422, detail="invalid chunk range")
        session = database.get_session(session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="unknown session")
        if session["state"] == "published_verified":
            raise HTTPException(status_code=409, detail="session is already published")
        body = await request.body()
        if not body:
            raise HTTPException(status_code=422, detail="empty audio chunk")
        if len(body) > settings.max_chunk_bytes:
            raise HTTPException(status_code=413, detail="audio chunk exceeds configured limit")
        actual_sha = hashlib.sha256(body).hexdigest()
        if actual_sha != x_chunk_sha256.lower():
            raise HTTPException(status_code=422, detail="SHA-256 mismatch")

        session_dir = settings.audio_dir / session_id
        session_dir.mkdir(parents=True, exist_ok=True)
        target = session_dir / f"{chunk_index:05d}-{actual_sha[:12]}.wav"
        temporary = target.with_suffix(".wav.part")
        temporary.write_bytes(body)
        os.replace(temporary, target)
        try:
            _row, duplicate = database.put_chunk(
                session_id=session_id,
                chunk_index=chunk_index,
                start_ms=x_chunk_start_ms,
                end_ms=x_chunk_end_ms,
                sha256=actual_sha,
                local_path=str(target),
            )
        except ValueError as exc:
            target.unlink(missing_ok=True)
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        processor.notify()
        return ChunkReceipt(
            session_id=session_id,
            chunk_index=chunk_index,
            sha256=actual_sha,
            accepted=True,
            duplicate=duplicate,
        )

    @router.post(
        "/v1/sessions/{session_id}/complete",
        response_model=SessionProgress,
        dependencies=[Depends(authenticator.verify)],
    )
    def complete_session(session_id: str, payload: SessionComplete) -> SessionProgress:
        try:
            database.finish_session(
                session_id=session_id,
                ended_at=payload.ended_at,
                duration_ms=payload.duration_ms,
                expected_chunks=payload.chunk_count,
            )
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="unknown session") from exc
        uploaded = len(database.list_chunks(session_id))
        if uploaded > payload.chunk_count:
            raise HTTPException(
                status_code=409,
                detail="declared chunk count is lower than chunks already received",
            )
        processor.notify()
        return _progress(database, session_id)

    @router.get(
        "/v1/sessions/{session_id}",
        response_model=SessionProgress,
        dependencies=[Depends(authenticator.verify)],
    )
    def get_session(session_id: str) -> SessionProgress:
        return _progress(database, session_id)

    return router


def _progress(database: Database, session_id: str) -> SessionProgress:
    progress = database.progress(session_id)
    if progress is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="unknown session")
    return SessionProgress(
        session_id=session_id,
        state=str(progress["state"]),
        recording_finished=bool(progress["finished"]),
        chunks_expected=progress["expected_chunks"],
        chunks_uploaded=int(progress["chunks_uploaded"]),
        chunks_transcribed=int(progress["chunks_transcribed"]),
        upload_progress=float(progress["upload_progress"]),
        transcription_progress=float(progress["transcription_progress"]),
        summary_ready=bool(progress["summary_json"]),
        github_verified=progress["state"] == "published_verified",
        github_url=progress["github_url"],
        github_commit_sha=progress["github_commit_sha"],
        last_error=progress["last_error"],
        retry_after_seconds=progress["retry_after_seconds"],
    )
