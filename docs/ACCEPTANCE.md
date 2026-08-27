# MVP acceptance

## Product result

Одна намеренно завершённая голосовая сессия, включая любое число пауз и технических чанков, создаёт ровно один source packet и одну незакрытую intake-запись в `idea-hub/main`.

## Required gates

- Backend unit tests and lint pass.
- Production Docker image builds from the pinned `events-bot-new` limiter source.
- Android unit tests pass and GitHub Actions produces an installable debug APK.
- Gemini model ID contains `flash-lite`; fallback to non-Lite models is rejected.
- Every provider send is admitted by the shared Supabase limiter; local/fail-open limiter modes are disabled.
- Phone and backend retain audio until exact GitHub commit readback.
- Phone and backend remove audio after verified publication, including cleanup after a crash between verification and deletion.
- A quota refusal is visible as `waiting_for_quota`, not as a generic success or silent stall.
- GitHub commit message contains only the session ID, never dictated content.

## Hardware scenarios

See `docs/ADB_HANDOFF.md`. MVP is not accepted on emulator evidence alone.
