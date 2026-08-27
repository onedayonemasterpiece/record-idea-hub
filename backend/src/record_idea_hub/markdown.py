from __future__ import annotations

import json
import re
from datetime import UTC, datetime
from typing import Any

import yaml

from .models import SummaryPayload


def _yaml_scalar(value: str) -> str:
    return yaml.safe_dump(value, allow_unicode=True, default_flow_style=True).strip()


def _section(title: str, values: list[str]) -> str:
    if not values:
        return f"## {title}\n\n_Не выделено моделью._\n"
    return f"## {title}\n\n" + "\n".join(f"- {value}" for value in values) + "\n"


def format_ms(value: int) -> str:
    total_seconds = max(0, int(value) // 1000)
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours:02d}:{minutes:02d}:{seconds:02d}"
    return f"{minutes:02d}:{seconds:02d}"


def render_source_packet(
    *,
    session: dict[str, Any],
    chunks: list[dict[str, Any]],
    summary: SummaryPayload,
    model: str,
    registered_at: str,
) -> str:
    frontmatter = {
        "session_id": session["session_id"],
        "overall_status": "open",
        "source_kind": "android_voice_session",
        "recorded_at": session["started_at"],
        "ended_at": session["ended_at"],
        "timezone": session["timezone"],
        "duration_seconds": round(int(session["duration_ms"]) / 1000, 1),
        "device": session["device_label"],
        "language": "ru",
        "transcription_model": model,
        "summary_model": model,
        "prompt_version": "voice-intake-v1",
        "processing_status": "pending",
        "registered_at": registered_at,
        "audio_retention": "deleted_after_github_readback",
        "tags": summary.tags,
    }
    header = yaml.safe_dump(
        frontmatter,
        allow_unicode=True,
        sort_keys=False,
        default_flow_style=False,
    ).strip()
    body = [
        "---",
        header,
        "---",
        "",
        f"# {summary.title.strip()}",
        "",
        "> **Статус:** расшифровка и выжимка готовы; требуется маршрутизация и "
        "материализация идей в канонические документы IdeaHub.",
        "",
        "## Коротко",
        "",
        summary.short_summary.strip(),
        "",
        "## Подробная выжимка",
        "",
        summary.detailed_summary.strip(),
        "",
        _section("Основные тезисы", summary.theses).rstrip(),
        "",
        _section("Идеи и предложения", summary.ideas).rstrip(),
        "",
        _section("Решения", summary.decisions).rstrip(),
        "",
        _section("Задачи и следующие действия", summary.tasks).rstrip(),
        "",
        _section("Факты и конкретика", summary.facts).rstrip(),
        "",
        _section("Упомянутые сущности", summary.entities).rstrip(),
        "",
        _section("Связанные проекты", summary.related_projects).rstrip(),
        "",
        _section("Открытые вопросы", summary.open_questions).rstrip(),
        "",
        _section("Противоречия", summary.contradictions).rstrip(),
        "",
        _section("Неопределённые фрагменты", summary.uncertain_fragments).rstrip(),
        "",
        "## Полная расшифровка",
        "",
    ]
    for chunk in chunks:
        transcript = json.loads(chunk["transcript_json"])
        body.extend(
            [
                f"### {format_ms(chunk['start_ms'])}–{format_ms(chunk['end_ms'])}",
                "",
                str(transcript["transcript"]).strip(),
                "",
            ]
        )
    body.extend(
        [
            "## Техническая фиксация",
            "",
            f"- Сессия: `{session['session_id']}`",
            f"- Чанков: {len(chunks)}",
            f"- Модель: `{model}`",
            "- Исходное аудио удаляется с телефона и сервера только после "
            "подтверждённого GitHub readback.",
            "- Текст идеи намеренно отсутствует в сообщении commit.",
            "",
        ]
    )
    return "\n".join(body).rstrip() + "\n"


def build_registry_entry(
    *,
    session: dict[str, Any],
    summary: SummaryPayload,
    detail_path: str,
    registered_at: str,
) -> dict[str, Any]:
    return {
        "session_id": session["session_id"],
        "title": summary.title.strip(),
        "session_kind": "idea_intake",
        "detail_path": detail_path,
        "occurred_at": {
            "start": session["started_at"],
            "end": session["ended_at"],
            "timezone": session["timezone"],
        },
        "registered_at": registered_at,
        "source": {
            "platform": "record-idea-hub-android",
            "source_kind": "owner_voice_session",
            "media": ["voice", "transcription"],
            "direct_url": None,
            "locator": f"Android voice session {session['session_id']}",
            "link_status": "local_device_source_no_public_url",
            "authorization": "owner_recorded_single_user_device",
        },
        "routes": {
            "primary_contexts": ["portfolio.inbox"],
            "destinations": [{"role": "source_packet", "path": detail_path}],
        },
        "status": {
            "overall": "open",
            "capture": "complete",
            "transcription": "complete",
            "normalization": "complete",
            "routing": "pending",
            "processing": "pending",
            "materialization": "pending",
            "verification": "complete",
        },
        "counts": {
            "unit": "voice_sessions",
            "observed": 1,
            "transcribed": 1,
            "normalized": 1,
            "routed": 0,
            "processed": 0,
            "excluded": 0,
            "pending": 1,
        },
        "quality_flags": [
            "source_packet_github_readback_verified",
            "single_session_not_per_chunk",
        ],
        "open_items": [
            "Route extracted ideas and materialize them into owning canonical documents."
        ],
    }


def insert_registry_entry(
    current_text: str,
    *,
    entry: dict[str, Any],
    updated_at: str,
) -> str:
    parsed = yaml.safe_load(current_text)
    if not isinstance(parsed, dict) or not isinstance(parsed.get("sessions"), list):
        raise ValueError("registry/intake-sessions.yaml has an unexpected shape")
    session_id = entry["session_id"]
    if any(item.get("session_id") == session_id for item in parsed["sessions"]):
        return current_text
    has_block_sessions = "sessions:\n" in current_text
    has_inline_empty_sessions = bool(re.search(r"(?m)^sessions:\s*\[\]\s*$", current_text))
    if not has_block_sessions and not has_inline_empty_sessions:
        raise ValueError("registry/intake-sessions.yaml is missing a writable sessions list")
    block = yaml.safe_dump(
        [entry],
        allow_unicode=True,
        sort_keys=False,
        default_flow_style=False,
        width=100,
    ).rstrip()
    updated = re.sub(
        r"(?m)^updated_at:\s*.*$",
        f"updated_at: {_yaml_scalar(updated_at)}",
        current_text,
        count=1,
    )
    if has_block_sessions:
        updated = updated.replace("sessions:\n", f"sessions:\n{block}\n\n", 1)
    else:
        updated = re.sub(
            r"(?m)^sessions:\s*\[\]\s*$",
            f"sessions:\n{block}",
            updated,
            count=1,
        )
    # Parse the generated file before any GitHub write.
    validated = yaml.safe_load(updated)
    if not any(
        item.get("session_id") == session_id for item in validated.get("sessions", [])
    ):
        raise ValueError("generated registry does not contain the new session")
    return updated


def detail_path_for(session_id: str, started_at: str) -> str:
    date_part = started_at[:10]
    try:
        parsed = datetime.fromisoformat(date_part)
    except ValueError:
        parsed = datetime.now(UTC)
    return f"registry/sessions/{parsed.year:04d}/{parsed.month:02d}/{session_id}.md"
