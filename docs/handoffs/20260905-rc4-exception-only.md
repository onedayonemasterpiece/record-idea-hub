# Local OpenCode: one exception capture on installed RC4, NOT a new recording

The phone report is PR #5 comment 5551525483. The owner's no-uninstall,
no-data-clear and no-new-generation restrictions remain in force.

## Boundary

The original export does not contain the exact Samsung exception. Injected
HTTP/JSON/SQLite tests are NOT that phone exception. The readback changes lock
accepted complete into GET-only operation and retain safe diagnostics; they are
NOT a confirmed repair of the observed physical failure.

RC4 is installed as `com.onedayonemasterpiece.recordideahub.v11`, versionCode 5.
Its known certificate SHA256 is
`d76232a3b9b298bb31f244dcd47a8f8d8cf2f8c02501cd8a46f2f6aea0694a95`.
The checked CI used a disposable runner debug key without keystore persistence.
A matching available private key has not been established. New CI outputs are
**unsigned evidence**, NOT installable updates. Do not generate another random
key, search for the obsolete RC2 key, uninstall RC4 or publish private keys.

## One bounded action

Use the existing PR checkout; Python 3 and JDK 17+ (`java`, `javac`, `jdk.jdi`)
are required. Observe the already running app; opening its normal UI is allowed.
Do not force-stop, clear data, start recording, invoke an unexported service,
edit SQLite or call `complete`. Check that the displayed unfinished session is
still `voice-20260905-131734-5d652699`. Do not attribute an exception to that ID
if other deliveries are running: this probe deliberately does not inspect locals.

From repository root on Windows:

```powershell
python tools/readback/capture_rc4_exception.py --adb C:\platform-tools\adb.exe --serial R5CNC1DQ23H --seconds 90 --output rc4-readback-exception.json
```

The wrapper selects the app **PID**, creates its own temporary ADB JDWP forward
and removes only that forward on exit. It observes one exception caught by
`SyncEngine.run` or times out. It briefly suspends the event thread to read code
metadata, resumes it in `finally`, and detaches. It never invokes target methods
or reads messages, causes, fields, arguments, locals, response body, settings,
token or audio. HTTP status is `null`, not guessed.

This helper was checked against a synthetic local JVM exception, NOT this Samsung.
If attach is unsupported or no matching event occurs, report that result once.
Do not broaden to an intrusive debug marathon, change battery policy, press Retry
repeatedly or create new audio. No matching event is not PASS.

Post only its compact metadata JSON and session/time correlation to PR #5.
Never attach the full OpenCode export or unrestricted logcat. This is input for a
targeted root-cause repair in ChatGPT, not permission for OpenCode to alter Android
sources or repeat inference.

## Remaining acceptance

After the exact exception is repaired and a compatible **signed** update is
verified, restore the existing session through real status, without regeneration.
Require `published_verified`, `github_verified=true`, `server_audio_purged=true`
before app-owned local purge. A GitHub commit alone is insufficient. Preserve the
session ID, accepted-complete latch, receipts, quota and reconciliation state;
never force proofs in SQLite.

Only then perform at most one intelligible short phrase/screen-off control run
using the app UI/notification. Stop after one success. A screen Dozing report by
itself is not proof of full system Doze acceptance.
