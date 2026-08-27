# MVP architecture

## Product boundary

One deliberate recording session produces exactly one IdeaHub source packet. Technical chunk boundaries and pause/resume events never create separate Markdown records.

```text
Android AudioRecord
  ├─ recoverable PCM16/WAV chunks
  ├─ SQLite delivery ledger
  └─ WorkManager retry queue
          │ HTTPS + device token
          ▼
FastAPI backend + persistent /data
  ├─ idempotent chunk receipts
  ├─ events-bot-new GoogleAIClient
  │    └─ shared Supabase reserve / sent / finalize ledger
  ├─ Gemini Flash-Lite transcription
  ├─ Gemini Flash-Lite structured synthesis
  └─ atomic Git data transaction
          │
          ▼
idea-hub/main
  ├─ registry/sessions/YYYY/MM/<session_id>.md
  └─ registry/intake-sessions.yaml (overall=open, pending=1)
```

## Recording semantics

- PCM 16-bit, 16 kHz, mono, WAV.
- A chunk closes on a speech pause after 75 seconds or forcibly at 120 seconds.
- Explicit Pause closes the current chunk; Resume continues the same session timeline.
- Sessions shorter than five seconds are discarded locally and do not create IdeaHub noise.
- A `.wav.part` file is repaired and registered after process restart.

## Shared limiter

The backend imports the canonical `google_ai` package from a pinned `events-bot-new` commit during the Docker build. It does not copy or reimplement quota logic.

Production enforces:

```text
GOOGLE_AI_ALLOW_RESERVE_FALLBACK=0
GOOGLE_AI_LOCAL_LIMITER_FALLBACK=0
GOOGLE_AI_LOCAL_LIMITER_ON_RESERVE_ERROR=0
```

The configured model must contain `flash-lite`; the MVP default is `gemini-3.1-flash-lite`. Model fallback is disabled per request. The limiter receives distinct consumers for transcription and synthesis.

## Failure contract

At every moment a session is in one of two safe states:

1. its source audio still exists locally/server-side and delivery can resume; or
2. the exact GitHub commit has been read back and audio may be deleted.

An HTTP timeout after a GitHub ref update is reconciled by the deterministic `session_id` and detail path. No blind duplicate commit is allowed.
