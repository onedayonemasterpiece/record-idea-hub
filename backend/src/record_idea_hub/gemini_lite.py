from __future__ import annotations

import json
import math
import os
import re
from pathlib import Path
from typing import Any

from .errors import ConfigurationError, QuotaDeferred
from .models import SummaryPayload, TranscriptPayload


TRANSCRIPT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "transcript": {"type": "string"},
        "language": {"type": "string"},
        "uncertain_fragments": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["transcript", "language", "uncertain_fragments"],
    "additionalProperties": False,
}

SUMMARY_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "short_summary": {"type": "string"},
        "detailed_summary": {"type": "string"},
        "theses": {"type": "array", "items": {"type": "string"}},
        "ideas": {"type": "array", "items": {"type": "string"}},
        "decisions": {"type": "array", "items": {"type": "string"}},
        "tasks": {"type": "array", "items": {"type": "string"}},
        "facts": {"type": "array", "items": {"type": "string"}},
        "entities": {"type": "array", "items": {"type": "string"}},
        "related_projects": {"type": "array", "items": {"type": "string"}},
        "open_questions": {"type": "array", "items": {"type": "string"}},
        "contradictions": {"type": "array", "items": {"type": "string"}},
        "uncertain_fragments": {"type": "array", "items": {"type": "string"}},
        "tags": {"type": "array", "items": {"type": "string"}},
    },
    "required": [
        "title",
        "short_summary",
        "detailed_summary",
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
    ],
    "additionalProperties": False,
}

TRANSCRIBE_PROMPT = """Ты выполняешь точную расшифровку русской голосовой заметки владельца IdeaHub.

Верни только JSON по заданной схеме.

Правила:
- передай все содержательные слова и самокоррекции;
- не превращай расшифровку в пересказ;
- сохрани названия продуктов, репозиториев, организаций, фамилии, даты и числа;
- не добавляй фактов, которых нет в аудио;
- сомнительное место оставь в transcript как [неразборчиво] и кратко перечисли в uncertain_fragments;
- это один фрагмент более длинной сессии, поэтому не придумывай вступление или завершение.
"""

SUMMARY_PROMPT = """Ниже дана полная расшифровка одной голосовой рабочей сессии владельца IdeaHub.
Подготовь подробную, но доказательную структурированную выжимку и верни только JSON по схеме.

Обязательные правила:
- не теряй уникальные идеи, даже упомянутые вскользь;
- не выдавай гипотезу или размышление за принятое решение;
- не придумывай сроки, ответственных, факты или связи;
- отделяй задачи от идей, решения от предложений, факты от интерпретаций;
- сохраняй противоречия и неуверенность;
- title должен быть конкретным и пригодным как заголовок Markdown;
- detailed_summary должен позволять следующему агенту понять ход мысли без прослушивания аудио;
- язык ответа — русский.

РАСШИФРОВКА:
"""


class GeminiLiteService:
    """Gemini Flash-Lite calls routed through the canonical events-bot limiter."""

    def __init__(self, model: str) -> None:
        if "flash-lite" not in model.lower():
            raise ConfigurationError("only Gemini Flash-Lite is allowed")
        self.model = model
        self._transcribe_client: Any | None = None
        self._summary_client: Any | None = None

    @staticmethod
    def _load_clients() -> tuple[Any, Any, Any]:
        # Remote use is deliberately fail-closed. These values override a stray
        # environment inherited from a development shell.
        os.environ["GOOGLE_AI_ALLOW_RESERVE_FALLBACK"] = "0"
        os.environ["GOOGLE_AI_LOCAL_LIMITER_FALLBACK"] = "0"
        os.environ["GOOGLE_AI_LOCAL_LIMITER_ON_RESERVE_ERROR"] = "0"
        os.environ.setdefault("GOOGLE_AI_PROVIDER_TIMEOUT_SEC", "180")
        try:
            from google_ai.client import GoogleAIClient, InputTokenCount
            from google_ai.exceptions import RateLimitError, ReservationError
            from supabase import create_client
        except ImportError as exc:  # pragma: no cover - deployment packaging gate
            raise ConfigurationError(
                "events-bot google_ai package is missing; build through the pinned Dockerfile"
            ) from exc
        url = (os.getenv("GOOGLE_AI_LIMITER_SUPABASE_URL") or "").strip()
        key = (os.getenv("GOOGLE_AI_LIMITER_SUPABASE_SERVICE_KEY") or "").strip()
        normal_pool = (os.getenv("GOOGLE_AI_NORMAL_KEY_ENVS") or "").strip()
        if not url or not key:
            raise ConfigurationError("dedicated shared-limiter Supabase credentials are required")
        if not normal_pool:
            raise ConfigurationError(
                "GOOGLE_AI_NORMAL_KEY_ENVS must name the shared normal key pool"
            )
        supabase_client = create_client(url, key)
        transcribe = GoogleAIClient(
            supabase_client=supabase_client,
            consumer="record-idea-hub.transcribe.v1",
        )
        summarize = GoogleAIClient(
            supabase_client=supabase_client,
            consumer="record-idea-hub.summarize.v1",
        )
        # The application controls physical sends; disable hidden SDK retries.
        transcribe.hard_single_provider_attempt = True
        summarize.hard_single_provider_attempt = True
        return transcribe, summarize, (InputTokenCount, RateLimitError, ReservationError)

    def _ensure_clients(self) -> tuple[Any, Any, tuple[Any, Any, Any]]:
        if self._transcribe_client is None or self._summary_client is None:
            transcribe, summarize, types_ = self._load_clients()
            self._transcribe_client = transcribe
            self._summary_client = summarize
            self._limiter_types = types_
        return self._transcribe_client, self._summary_client, self._limiter_types

    @staticmethod
    def _parse_json(text: str) -> dict[str, Any]:
        cleaned = text.strip()
        match = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", cleaned, flags=re.S | re.I)
        if match:
            cleaned = match.group(1)
        value = json.loads(cleaned)
        if not isinstance(value, dict):
            raise ValueError("Gemini returned a non-object JSON value")
        return value

    @staticmethod
    def _quota_error(exc: Exception, rate_limit_type: type[Exception]) -> QuotaDeferred | None:
        if isinstance(exc, rate_limit_type):
            retry_ms = int(getattr(exc, "retry_after_ms", 0) or 0)
            reason = str(getattr(exc, "blocked_reason", None) or exc)
            return QuotaDeferred(reason, max(5, math.ceil(retry_ms / 1000)))
        return None

    async def transcribe(
        self,
        audio_path: Path,
        *,
        duration_ms: int,
    ) -> TranscriptPayload:
        transcribe_client, _, limiter_types = self._ensure_clients()
        InputTokenCount, RateLimitError, ReservationError = limiter_types
        try:
            from google.genai import types

            audio = audio_path.read_bytes()
            prompt = [
                TRANSCRIBE_PROMPT,
                types.Part.from_bytes(data=audio, mime_type="audio/wav"),
            ]
            # Current Gemini audio accounting is duration-based, not raw-byte
            # based. Use a conservative 35 tokens/sec plus prompt allowance so
            # the shared TPM ledger does not mistake a WAV file for text bytes.
            counted = InputTokenCount(
                tokens=max(1, math.ceil(duration_ms / 1000 * 35) + 700),
                source="audio_duration_35tps_plus_prompt",
                provider_model_name=f"models/{self.model}",
            )
            text, _usage = await transcribe_client.generate_content_async(
                model=self.model,
                prompt=prompt,
                generation_config={
                    "temperature": 0,
                    "response_mime_type": "application/json",
                    "response_schema": TRANSCRIPT_SCHEMA,
                },
                max_output_tokens=8192,
                allow_model_fallback=False,
                max_provider_attempts=2,
                input_token_count=counted,
                prompt_version="voice-transcribe-v1",
            )
            return TranscriptPayload.model_validate(self._parse_json(text))
        except Exception as exc:
            deferred = self._quota_error(exc, RateLimitError)
            if deferred:
                raise deferred from exc
            if isinstance(exc, ReservationError):
                raise ConfigurationError(str(exc)) from exc
            raise

    async def summarize(self, transcript: str) -> SummaryPayload:
        _, summary_client, limiter_types = self._ensure_clients()
        _InputTokenCount, RateLimitError, ReservationError = limiter_types
        try:
            text, _usage = await summary_client.generate_content_async(
                model=self.model,
                prompt=SUMMARY_PROMPT + transcript,
                generation_config={
                    "temperature": 0.1,
                    "response_mime_type": "application/json",
                    "response_schema": SUMMARY_SCHEMA,
                },
                max_output_tokens=16384,
                allow_model_fallback=False,
                max_provider_attempts=2,
                prompt_version="voice-summary-v1",
            )
            return SummaryPayload.model_validate(self._parse_json(text))
        except Exception as exc:
            deferred = self._quota_error(exc, RateLimitError)
            if deferred:
                raise deferred from exc
            if isinstance(exc, ReservationError):
                raise ConfigurationError(str(exc)) from exc
            raise
