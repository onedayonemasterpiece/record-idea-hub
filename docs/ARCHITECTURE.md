# MVP architecture

## Product boundary

One deliberately finished recording session produces exactly one IdeaHub source packet. Pause/resume events and technical chunk boundaries never create separate Markdown records.

```text
Samsung Android
  ├─ AudioRecord foreground service
  ├─ recoverable PCM16/WAV chunks
  ├─ SQLite: session, chunk and transcript state
  └─ WorkManager: network and quota retries
          │ HTTPS + one device token
          ▼
existing my-data-hub control-plane on devstand
  ├─ validates WAV, SHA-256 and request bounds
  ├─ shared Supabase limiter
  │    └─ reserve -> mark_sent -> one Google POST -> finalize
  ├─ Gemini Flash-Lite chunk transcription
  ├─ Gemini Flash-Lite structured session synthesis
  └─ atomic Git Data API transaction + readback
          │
          ▼
idea-hub/main
  ├─ inbox/voice/YYYY/MM/<session_id>.md
  ├─ registry/sessions/YYYY/MM/<session_id>.md
  └─ registry/intake-sessions.yaml (overall=open, pending=1)
```

## Ownership

The phone is the durable owner of:

- recording and pause/resume state;
- WAV chunks;
- per-chunk transcripts returned by the server;
- retry schedule and quota-wait state;
- the decision to delete audio after verified publication.

`my-data-hub` is a bounded processing boundary. It does not persist audio, does not introduce a second session database and does not own a queue. Request bytes live only during the HTTP handler.

## Recording semantics

- PCM 16-bit, 16 kHz, mono, WAV.
- A chunk closes around two minutes of recorded audio or when a useful fragment is closed by an explicit pause.
- Resume continues the same `session_id` and timeline.
- Only “Завершить и отправить” closes the logical session.
- Sessions shorter than five seconds are discarded locally and do not create IdeaHub noise.
- A closed `.wav.part` is repaired and registered after process restart.

## Google and quota contract

Only explicitly allowed Gemini Flash-Lite model IDs may be used. A provider request is impossible unless the shared limiter has returned a valid lease. There is no process-local limiter, fail-open path, unaccounted key rotation or hidden post-send retry.

A quota refusal is returned as typed HTTP 429 with `retry_after_seconds`. Android records `WAITING_FOR_QUOTA`, keeps all source data and schedules a delayed WorkManager run.

## GitHub contract

The server receives validated structured data, not arbitrary repository paths or arbitrary Markdown. It is hard-bound to `onedayonemasterpiece/idea-hub` and branch `main`.

Publication uses the current `main` tree as a base, creates one non-force commit, retries ordinary branch movement and reconciles an unknown network outcome by deterministic `session_id`. The phone receives success only after exact-commit and current-main readback.

## Failure invariant

At every moment one of these states is true:

1. the source WAV and all completed transcripts still exist on the phone and processing can resume; or
2. the exact IdeaHub publication has been read back and local WAV files may be deleted.

A generic spinner, HTTP timeout or process restart is never treated as proof of success.
