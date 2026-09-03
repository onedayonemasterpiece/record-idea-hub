# Android 1.1 architecture

## Product boundary

One deliberately completed recording session produces exactly one IdeaHub intake record. Manual pauses, automatic silence intervals and technical transport segments never create separate Markdown records.

```text
Samsung Android
  ├─ AudioRecord foreground microphone service
  ├─ adaptive energy gate + native fixed-point WebRTC VAD
  ├─ hardware-preferred AAC-LC/M4A, mono 16 kHz, 32 kbit/s
  ├─ SQLite WAL: session, segment, receipt and retry state
  └─ WorkManager: network-constrained create/upload/complete/status
          │ HTTPS + one device token
          ▼
existing my-data-hub control-plane on devstand
  ├─ durable idempotent session and segment receipts
  ├─ private temporary spool
  ├─ shared Supabase Google AI limiter
  │    └─ preflight → reserve → selected quota scope/key → mark_sent → POST → finalize
  ├─ one aggregate Gemini Flash-Lite transcription
  ├─ one text-only Gemini Flash-Lite structured synthesis
  └─ atomic IdeaHub transaction + exact/current-main readback
          │
          ▼
idea-hub/main
  ├─ inbox/voice/YYYY/MM/<session_id>.md
  ├─ registry/sessions/YYYY/MM/<session_id>.md
  ├─ registry/intake-sessions.yaml
  └─ inbox/voice/README.md chronological index
```

## Ownership

The phone is the durable owner of:

- active recording and manual pause/resume state;
- compact M4A transport segments;
- segment SHA-256 and local receipt state;
- retry schedule and server progress;
- the decision to delete local audio only after verified server completion.

`my-data-hub` durably owns the processing receipt and temporary server copy after upload. It may continue Gemini and GitHub work after the Android UI closes or the phone disconnects. Server audio is deleted only after IdeaHub exact-commit and current-main readback.

## Recording semantics

- Input: PCM16, 16 kHz, mono through `AudioRecord`.
- Output: AAC-LC in independently playable M4A containers, target 32 kbit/s.
- Automatic silence keeps the microphone active but stops encoder/file output.
- Manual pause stops the microphone.
- Detector: conservative energy gate followed by lightweight native WebRTC VAD; any detector failure is fail-open continuous recording.
- A RAM pre-roll and speech hangover protect phrase boundaries.
- A durable segment normally closes around three minutes of actually recorded audio, at manual pause or at explicit finish.
- Audio ranges across segments are exactly contiguous. Wall-clock ranges may
  overlap by at most 50 ms because a blocking `AudioRecord` read can deliver
  adjacent 30 ms frames faster than wall-clock sampling; this bounded jitter
  never changes audio order or duration.
- Only `Завершить и отправить` closes the logical session.
- Sessions shorter than the local minimum are discarded without creating IdeaHub noise.

## Synchronization semantics

Every v2 worker pass starts with an idempotent `POST /voice-intake/v2/sessions`. A local session is never treated as proof that the server has durable initialization state.

Uploads create receipts and make zero Gemini calls. After the exact complete manifest is accepted, the server performs asynchronous whole-session processing. For the priority review scenario up to roughly 20 minutes:

```text
1 aggregate audio transcription
+ 1 structured text synthesis
= 2 physical Gemini requests
```

Polling is sparse and persisted. Quota wait uses the server-provided `retry_at`; ambiguous post-send outcomes enter `reconciliation_required` and are never silently replayed.

## Google quota and key contract

Only explicitly allowed Gemini Flash-Lite models are accepted. The shared limiter selects from configured candidate keys by independent `quota_scope`; sibling keys from the same Google project are not treated as extra quota. Provider calls are impossible without a valid lease, and there is no direct-key fallback or hidden post-send retry.

## Failure invariant

At every moment one of these is true:

1. the local M4A source remains on the phone and synchronization can resume;
2. the server has a durable copy/receipt and can finish processing without the phone;
3. IdeaHub publication has passed exact/current-main readback, server audio is purged, and Android may delete its local segments.

A spinner, network timeout, local upload flag or provider response alone is never proof of terminal success.
