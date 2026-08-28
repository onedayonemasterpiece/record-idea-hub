# MVP acceptance

## Product result

Одна намеренно завершённая голосовая сессия, включая любое число пауз и технических чанков, создаёт ровно один source packet, один session detail и одну открытую pending-запись в `idea-hub/main`.

## Code and build gates

### Android repository

- `lintDebug`, `testDebugUnitTest` and `assembleDebug` pass from a clean GitHub Actions checkout.
- Workflow publishes artifact `record-idea-hub-debug-apk` containing `app-debug.apk`.
- The APK contains no Google, Supabase or GitHub credential.
- Record, pause, resume and explicit finish preserve one `session_id`.
- Per-chunk transcripts and retry state survive application process restart.

### my-data-hub repository

- Repository validator, secret scan, Ruff, strict mypy, pytest and PostgreSQL integration pass.
- Voice routes are mounted in the existing control-plane process, before its broad data catch-all.
- The configured model is explicitly allowed and contains `flash-lite`.
- Every physical Google request is admitted and finalized by the shared Supabase limiter.
- The server does not persist audio or create a separate session database/queue.
- GitHub publication is hard-bound to `onedayonemasterpiece/idea-hub`, branch `main`.

## Runtime gates on devstand

- Authenticated `/voice-intake/v1/health` is ready; unauthenticated access is rejected.
- Invalid WAV, oversized body and SHA mismatch are rejected before the limiter/provider.
- One real Russian audio smoke produces one reserve, one mark-sent, one Google request and one finalize record.
- A quota refusal is visible as `WAITING_FOR_QUOTA` with a concrete retry time.
- One disposable complete session creates a single neutral commit and passes exact-commit plus current-main readback.
- Logs contain no device token, provider key, GitHub credential, audio bytes or transcript content.

## Safety gates

- Phone keeps WAV files until `github_verified=true` is returned.
- `my-data-hub` never retains request audio after the handler returns.
- Android deletes local WAV files only after verified publication; failure to delete remains visible and retryable.
- Unknown GitHub update outcome is reconciled by deterministic `session_id`, never by blind duplicate commit.
- Commit message contains only `intake(voice): register <session_id>` and never dictated content.

## Hardware scenarios

See [`ADB_HANDOFF.md`](ADB_HANDOFF.md). MVP is not accepted on emulator or unit-test evidence alone; Samsung S21 Ultra screen-off recording, network interruption and post-readback cleanup must be verified through ADB.
