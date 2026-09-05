# OpenCode / ADB maintenance handoff

The previous Samsung S21 Ultra acceptance does not prove RC4 physical behavior.
For this rollout, server Codex deploys ONLY the backend. Local OpenCode downloads
an ALREADY BUILT CI APK, updates the phone and performs one focused smoke.
Never uninstall either populated package to work around signing.

Task documents:

- [Codex: backend only](handoffs/20260905-codex-server-rollout.md)
- [OpenCode: existing CI APK and phone smoke](handoffs/20260905-opencode-phone-smoke.md)

## Packages

```text
Android 1.0 (do not remove): com.onedayonemasterpiece.recordideahub
Android 1.1 / RC4 target:    com.onedayonemasterpiece.recordideahub.v11
```

## Existing APK — not a server deliverable

The verified RC4 APK already exists in `onedayonemasterpiece/record-idea-hub`:

```text
run_id:           33953071610
artifact_id:      9965493810
artifact_name:    record-idea-hub-1.1-rc4-apk
apk_filename:     record-idea-hub-1.1.0-rc4-debug.apk
apk_sha256:       1296361f1634c81f3d367561e0ac45c63d8321ede4ffa751a95446fab4a4ed84
certificate_sha256: d76232a3b9b298bb31f244dcd47a8f8d8cf2f8c02501cd8a46f2f6aea0694a95
PR head SHA:      d9441d057501bd49b6d66b0f653c96ffa0bf859c
Built merge SHA:  b73eadeb85114456247fcb6ba52df77f01985825
version:          1.1.0-rc4 / versionCode 5
```

The built merge SHA is the tested PR ref, not a claim that main was updated.
SOURCE_SHA.txt describes that tested ref. Later documentation-only commits do
not require rebuilding the unchanged APK. APK SHA256 is not the artifact ZIP hash.

```bash
gh run download 33953071610 -R onedayonemasterpiece/record-idea-hub \
  -n record-idea-hub-1.1-rc4-apk -D "$NEW_OUTPUT_DIR"
```

Verify run success, artifact identity, source identities, APK SHA256 and
`apksigner verify --print-certs`. Do not select arbitrary latest/main builds.
Do not ask server Codex to create, download, sign or hand over an APK or merge
Android #5. Main-only installation and new post-merge build requirements are removed.

Separately read `my-data-hub#39` for the actual backend deployment/smoke result,
normally marked `BACKEND_READY`. A previous `READY_FOR_PHONE` report is usable
only if it provides actual deployed-source/API/worker evidence. A mandatory
`docs/verification/20260905-server-rollout.json` is no longer required: a factual
comment in #39 suffices. Download/signature checks may precede backend readiness;
installation and a live recording require the backend confirmation.

## In-place update

```bash
adb devices -l
adb -s "$SERIAL" shell pm path com.onedayonemasterpiece.recordideahub.v11
```

Select the authorized device explicitly. Do not bypass RSA/device lock. Confirm
no recording is active, record the current version/unfinished queue, and keep
a consistent private backup where accessible. A live SQLite copy without WAL
or coordination is not a consistent backup; encrypted preferences do not
replace Android Keystore. Never publish the backup, credentials or personal audio.

Pull the installed base.apk and compare its signing certificate with the
candidate. When the signature is compatible and this is not a downgrade:

```bash
adb -s "$SERIAL" install -r "$APK"
```

Skip reinstalling an identical installed build. CI DEBUG does not prove the
same signing identity. For a mismatch, check only the known local debug.keystore
and documented project signing store. An accessible matching original key may
sign a copy of the verified APK without changing its code/resources/package;
record original and installed hashes separately. Do not publish the private key.
If there is no matching key, report `BLOCKED_SIGNATURE` and retain the queue.
Do not generate a replacement key or pass the APK task to server Codex.
Never use `adb uninstall`, `pm clear`, `-d`, root or signature-check bypasses.

## Configuration and permissions

Retain existing backend configuration and device token. Do not re-provision a
working token just because the APK changed. Grant only missing permissions
through the normal device/ADB flow:

```bash
PKG=com.onedayonemasterpiece.recordideahub.v11
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO
# Android 13+ only, when missing:
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
```

For genuinely unconfigured installations use the existing approved private
provisioning process. Expected server origin: `https://mcp-datahub.kenigevents.ru`.
Debug activity:
`com.onedayonemasterpiece.recordideahub.v11/com.onedayonemasterpiece.recordideahub.MainActivity`.
Debug extras `server_url` and `device_token` are supported, but never embed
literal credentials in generated scripts, shell history, tool output or reports.
Google/Supabase/GitHub credentials never go to the phone.

## One focused smoke

Follow the phone handoff: short nonpersonal speech, Finish, immediate screen-off,
observe delivery, then verify the actual IdeaHub packet and all confirmations:

```text
published_verified
github_verified=true
server_audio_purged=true
```

Verify local test-audio cleanup only after these confirmations and preserve
older unfinished recordings. Do not equate upload-only, HTTP 401 or a UI stage
counter with end-to-end success. Network interruption is optional when easy;
long-duration/Doze/process-death/reboot matrices are not required for this smoke.

## Targeted diagnostics

Use app/time-window logs rather than deleting global device logs. Record exact
APK/source identity, model/Android, session_id, failed transition, safe backend
status, GitHub commit/path and purge result. Do not publish full logs, tokens,
personal transcript or backup. A clipped icon or nonfatal MPEG4Writer warning
is not a blocker without a correlated crash or invalid/lost audio.

If a confirmed media defect needs extraction, use a binary-safe host process:

```python
import subprocess
from pathlib import Path

adb = r"<absolute adb path>"
serial = "<selected authorized device>"
pkg = "com.onedayonemasterpiece.recordideahub.v11"
remote = "files/audio/<actual-file>.m4a"
local = Path(r"<private-evidence-directory>/sample.m4a")
with local.open("wb") as output:
    subprocess.run(
        [adb, "-s", serial, "exec-out", "run-as", pkg, "cat", remote],
        stdout=output, check=True,
    )
```

Validate only when needed with ffprobe: M4A/MP4, AAC-LC, 16 kHz mono,
approximately 32 kbit/s. Do not extract personal recordings to embellish a report.
After one successful agreed smoke, save a short result in #5 and stop.
