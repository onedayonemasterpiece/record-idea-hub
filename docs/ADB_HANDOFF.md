# OpenCode / ADB maintenance handoff

The initial Samsung S21 Ultra acceptance is complete. Use this document for
updating a later build, reproducing a confirmed defect or validating a focused
follow-up change. Never uninstall either populated package to work around signing.

For the RC4 rollout, use these ordered task documents:

- [`handoffs/20260905-codex-server-rollout.md`](handoffs/20260905-codex-server-rollout.md)
- [`handoffs/20260905-opencode-phone-smoke.md`](handoffs/20260905-opencode-phone-smoke.md)

## Current packages

```text
Android 1.0:
com.onedayonemasterpiece.recordideahub

Android 1.1 side-by-side build:
com.onedayonemasterpiece.recordideahub.v11
```

## Obtain the APK

For RC4, first read the backend rollout result on main:
`docs/verification/20260905-server-rollout.json`. The server operator creates
this file after deployment; its absence is not proof that deployment succeeded.
Require `READY_FOR_PHONE` before installation or a new live phone test.

Use the exact successful run and artifact recorded there. The RC4 workflow
artifact is `record-idea-hub-1.1-rc4-apk`. Do not select an arbitrary latest
successful main run: it may belong to another revision or an older RC.

```bash
REPO=onedayonemasterpiece/record-idea-hub
# Fill from the verified rollout receipt, not from a guessed latest build.
gh run download "$RUN_ID" -R "$REPO" -n "$ARTIFACT_NAME" -D "$NEW_OUTPUT_DIR"
```

Verify run/source SHA, artifact ID/name, APK SHA256, package/version and
`apksigner verify --print-certs` against the receipt. Record the exact binary
that is actually installed, including a new hash after any authorized re-signing.

## Update in place without deleting recordings

```bash
adb devices -l
adb -s "$SERIAL" shell pm path com.onedayonemasterpiece.recordideahub.v11
```

Pull the installed base APK and compare its signing certificate with the
candidate before installation. Verify that no recording is active and record
the existing queue. Keep a consistent private backup where accessible.
A backup of encrypted preferences is not a replacement for the app's Keystore.

When the signing identity is compatible and this is not a downgrade:

```bash
adb -s "$SERIAL" install -r "$APK"
```

A CI debug APK does not automatically share the installed debug signature.
If signatures differ, use an existing accessible signing key only after its
certificate matches the installed app. If no such key is available, report
`BLOCKED_SIGNATURE` with the two public certificate fingerprints and preserve
the app and queue. Do not generate a new key as a workaround.

Never use `adb uninstall`, `pm clear`, root or signature-check bypasses as part
of this maintenance. Do not remove the Android 1.0 package either.

Grant only needed permissions through the normal device/ADB permission flow:

```bash
PKG=com.onedayonemasterpiece.recordideahub.v11
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO
# Android 13+ only, and only when notification permission is missing:
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
```

## Provisioning

Existing installations retain configuration during a compatible update. Do not
re-provision a working token simply because a new APK was installed.

For a genuinely unconfigured installation, inputs are private:

```text
SERVER_URL=https://mcp-datahub.kenigevents.ru
DEVICE_TOKEN=<single-device bearer token>
```

Use the existing approved local provisioning process and secret store; never
print the token in tool output, shell history or reports. The debug activity is
`com.onedayonemasterpiece.recordideahub.v11/com.onedayonemasterpiece.recordideahub.MainActivity`.
The app supports the debug extras `server_url` and `device_token`, but do not
place literal credentials in generated scripts or public command transcripts.
The application uses Android Keystore-backed encryption. Google, Supabase and
GitHub credentials never go to the phone.

## Focused smoke test

For RC4, one short spoken test followed by Finish and immediate screen-off is
the required physical smoke; follow the phone handoff. Preserve the existing
queue. The terminal confirmations are:

```text
published_verified
github_verified=true
server_audio_purged=true
```

Verify the actual IdeaHub packet and local cleanup after these confirmations.
Do not equate upload-only, an HTTP 401, or a UI stage counter with completed
end-to-end acceptance. Manual-pause/VAD, long-duration and process-death tests
are focused follow-ups only when a related regression is being investigated.

## Binary-safe M4A extraction

When needed for a confirmed media defect, use `exec-out` with a binary-safe
host process, not a text shell redirect. Keep the extracted audio private.

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
        stdout=output,
        check=True,
    )
```

Validate with `ffprobe`: MP4/M4A container, AAC-LC, 16 kHz, mono and approximately
32 kbit/s. Do not extract personal recordings just to embellish a smoke report.

## Diagnostics and stop condition

Prefer targeted app/process logcat and the relevant time window; do not clear
all device logs before preserving evidence of an existing failure.
Useful diagnostics are app services, the selected JobScheduler/WorkManager
job, actual device/Android version and safe typed backend error/status.
Record APK/source identity, session_id, failed state transition, IdeaHub
commit/path and whether server/local purge occurred. Never include tokens,
provider keys, audio bytes, full personal transcripts or private backups.

A clipped adaptive icon and Samsung's non-fatal `MPEG4Writer: Stop() called but
track is not started or stopped` warning are not blockers without a correlated
crash or invalid/lost M4A. After the agreed smoke passes, stop; do not reopen a
cosmetic audit.
