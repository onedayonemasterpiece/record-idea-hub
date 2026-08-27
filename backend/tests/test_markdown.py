from __future__ import annotations

import json

import yaml

from record_idea_hub.github_writer import IdeaHubGitHubWriter
from record_idea_hub.markdown import (
    build_registry_entry,
    insert_registry_entry,
    render_source_packet,
)
from record_idea_hub.models import SummaryPayload


def sample_session() -> dict:
    return {
        "session_id": "voice-20260827-abc12345",
        "started_at": "2026-08-27T12:00:00-04:00",
        "ended_at": "2026-08-27T12:03:00-04:00",
        "timezone": "America/Indiana/Indianapolis",
        "device_label": "Samsung S21 Ultra",
        "duration_ms": 180000,
        "created_at": 1787851200.0,
    }


def sample_summary() -> SummaryPayload:
    return SummaryPayload(
        title="Новая схема голосового inbox",
        short_summary="Нужно быстро фиксировать голосовые идеи.",
        detailed_summary="Пользователь описал устойчивый однокнопочный процесс.",
        theses=["Одна сессия — один документ"],
        ideas=["Записывать с паузами"],
        decisions=["Использовать Gemini Flash-Lite"],
        tasks=["Проверить сборку APK"],
        facts=["Целевой телефон — Samsung S21 Ultra"],
        entities=["Gemini", "IdeaHub"],
        related_projects=["record-idea-hub"],
        open_questions=[],
        contradictions=[],
        uncertain_fragments=[],
        tags=["voice", "idea-hub"],
    )


def test_source_packet_has_required_frontmatter_and_transcript() -> None:
    chunks = [
        {
            "chunk_index": 0,
            "start_ms": 0,
            "end_ms": 90000,
            "transcript_json": json.dumps(
                {"transcript": "Проверка расшифровки.", "language": "ru"}
            ),
        }
    ]
    rendered = render_source_packet(
        session=sample_session(),
        chunks=chunks,
        summary=sample_summary(),
        model="gemini-3.1-flash-lite",
        registered_at="2026-08-27T16:03:00Z",
    )
    assert rendered.startswith("---\n")
    _, frontmatter, body = rendered.split("---", 2)
    meta = yaml.safe_load(frontmatter)
    assert meta["session_id"] == "voice-20260827-abc12345"
    assert meta["overall_status"] == "open"
    assert meta["processing_status"] == "pending"
    assert "Проверка расшифровки" in body
    assert "требуется маршрутизация" in body


def test_registry_insertion_is_valid_and_idempotent() -> None:
    current = """schema_version: 1.0.0
registry_id: idea-hub-intake-sessions
updated_at: '2026-08-26T00:00:00Z'
sessions:
- session_id: existing-session
  title: Existing
"""
    entry = build_registry_entry(
        session=sample_session(),
        summary=sample_summary(),
        detail_path="registry/sessions/2026/08/voice-20260827-abc12345.md",
        registered_at="2026-08-27T16:03:00Z",
    )
    updated = insert_registry_entry(
        current,
        entry=entry,
        updated_at="2026-08-27T16:03:00Z",
    )
    parsed = yaml.safe_load(updated)
    assert parsed["sessions"][0]["session_id"] == "voice-20260827-abc12345"
    assert parsed["sessions"][0]["counts"]["pending"] == 1
    assert parsed["sessions"][0]["status"]["processing"] == "pending"
    assert insert_registry_entry(
        updated,
        entry=entry,
        updated_at="2026-08-27T16:05:00Z",
    ) == updated


def test_generated_registry_passes_current_contract_shape() -> None:
    current = """schema_version: 1.0.0
registry_id: idea-hub-intake-sessions
updated_at: '2026-08-26T00:00:00Z'
sessions: []
"""
    entry = build_registry_entry(
        session=sample_session(),
        summary=sample_summary(),
        detail_path="registry/sessions/2026/08/voice-20260827-abc12345.md",
        registered_at="2026-08-27T16:03:00Z",
    )
    updated = insert_registry_entry(
        current, entry=entry, updated_at="2026-08-27T16:03:00Z"
    )
    schema = {
        "type": "object",
        "required": ["schema_version", "registry_id", "updated_at", "sessions"],
        "properties": {
            "schema_version": {"const": "1.0.0"},
            "registry_id": {"type": "string"},
            "updated_at": {"type": "string", "format": "date-time"},
            "sessions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["session_id", "status", "counts"],
                },
            },
        },
    }
    IdeaHubGitHubWriter.validate_registry_document(updated, json.dumps(schema))
