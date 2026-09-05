# Android RC4 delivery reliability — 2026-09-05

Source change, not a production/phone acceptance claim.
Base: `35fb24ee20ef2845824032793dedb60aee7ea6fa`.
Companion backend: `onedayonemasterpiece/my-data-hub#39`.

## Implemented

- Finish starts an explicit user transfer, independent of the microphone foreground service.
- API 34+ uses a user-initiated data-transfer JobService with a system notification. The component is disabled on older OS versions. Older versions and rejected UIDT scheduling use expedited foreground WorkManager delivery with the `dataSync` service type.
- One durable WorkManager chain per full session ID replaces the global delayed chain. A separately named user transfer need not wait behind an old polling delay. A session lock prevents concurrent in-process UIDT/WorkManager uploads; a periodic watchdog rediscovers unfinished sessions.
- Cancellation disconnects the active HTTP connection, interrupts the executor and preserves audio/receipts. Interrupted transfers are not misclassified as corrupt local data.
- Database v4 stores the immutable create envelope before its first send. An app update does not regenerate the original session metadata. The backend compatibility change tolerates only historical client_version telemetry drift.
- Once the server reports recording_finished, the client observes progress instead of repeatedly sending complete. Server retries own processing from that point.
- Reconciliation rows remain visible to read-only polling, including after server-side repair. The UI does not erase reconciliation or quota state when the user asks to check/retry. A failed read of a reconciliation row cannot reopen upload or paid processing.
- Three confirmations are required before local deletion: published_verified, github_verified, server_audio_purged. Migration of old completed rows with remaining files requests fresh server proof rather than trusting an old label.

## Automated checks

Existing unit tests remain. New pure Kotlin tests cover queue identity, the three-part deletion gate, no complete replay and HTTP cancellation. Robolectric tests exercise actual SQLite v3-to-v4 migration, stable create-envelope storage across process-like close/reopen, reconciliation visibility and protection against deletion based only on a status label. CI executes lintDebug, testDebugUnitTest and assembleDebug, and records source SHA, APK SHA256 and the public signing-certificate fingerprint.

These are not screen-off/Doze/hardware tests. Notifications and user-initiated scheduling are OS-managed; force-stop, task-manager Stop, thermal limits and network loss can stop or defer work. Durable resumption does not mean uninterruptible execution.

## Deployment and acceptance

1. Deploy the companion backend first; preserve existing spool, SQLite ledger and session identities. Do not reset ambiguous rows or authorize another paid call by rewriting their state.
2. Compare the installed package/signing certificate with the candidate APK. CI debug signing is not a stable distribution identity. Never uninstall a populated app to bypass a signature mismatch. Use the existing signing key for an in-place upgrade, or resolve signing/migration separately without deleting queued recordings.
3. On the physical Samsung, test Finish -> immediate screen off, constrained network, network return, process death, multiple queued recordings and server repair while the app is closed. Record actual OS stop reasons and final GitHub readback.
4. Verify the client stays offline after a fully accepted complete while the backend autonomously finishes safe retries, publication and purge. Reconnect and confirm local cleanup only after both server proofs.
5. Check a populated v3 queue survives upgrade with unchanged audio hashes/session IDs and no duplicate IdeaHub packet.
6. A real host reboot and public HTTPS ingress/worker recovery remain an owner-approved infrastructure acceptance gate. The existing vpn-server restart policy is not altered here.

The recorder's unfinished M4A-tail recovery and a stable APK distribution-signing setup are not implemented by this delivery refactor. No APK installation, production deployment, host reboot, real provider call or destructive cleanup was performed during source development.
