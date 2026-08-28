# OpenCode / ADB maintenance handoff

The initial Samsung S21 Ultra acceptance is complete. Use this document for reinstalling a later build, reproducing a confirmed defect or validating a focused follow-up change. Do not remove the working Android 1.0 package unless explicitly requested.

## Current packages

```text
Android 1.0:
com.onedayonemasterpiece.recordideahub

Android 1.1 side-by-side build:
com.onedayonemasterpiece.recordideahub.v11
```

## Obtain the APK

Use the latest successful `Android CI and APK` run on `main`. The current workflow artifact is:

```text
record-idea-hub-1.1-rc2-apk
```

Example:

```bash
REPO=onedayonemasterpiece/record-idea-hub
RUN_ID="$(gh run list -R "$REPO" --workflow ci.yml --branch main --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
rm -rf .tmp-record-idea-apk
mkdir -p .tmp-record-idea-apk
gh run download -R "$REPO" "$RUN_ID" -n record-idea-hub-1.1-rc2-apk -D .tmp-record-idea-apk
APK="$(find .tmp-record-idea-apk -name '*.apk' -type f -print -quit)"
test -f "$APK"
sha256sum "$APK"
```

Record run ID, head SHA, artifact ID/name and APK SHA-256. Do not substitute a local build for final evidence.

## Install side by side

```bash
adb devices -l
adb install -r "$APK"
```

If only the previous 1.1 debug signature conflicts, remove exactly this package and reinstall:

```bash
adb uninstall com.onedayonemasterpiece.recordideahub.v11
adb install "$APK"
```

Never remove `com.onedayonemasterpiece.recordideahub` as part of 1.1 maintenance.

Grant permissions:

```bash
PKG=com.onedayonemasterpiece.recordideahub.v11
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
```

## Provisioning

Inputs are private and must not be printed in logs or reports:

```text
SERVER_URL=https://mcp-datahub.kenigevents.ru
DEVICE_TOKEN=<single-device bearer token>
```

Debug provisioning:

```bash
set +x
adb shell am start \
  -n com.onedayonemasterpiece.recordideahub.v11/com.onedayonemasterpiece.recordideahub.MainActivity \
  --es server_url "$SERVER_URL" \
  --es device_token "$DEVICE_TOKEN"
unset DEVICE_TOKEN
```

The application stores the token through Android Keystore-backed encryption. Google, Supabase and GitHub credentials never go to the phone.

## Focused smoke test

1. Start recording.
2. Observe automatic-silence state during quiet input: microphone remains active, saved-audio timer is stopped.
3. Speak a short control sentence.
4. Use manual pause: microphone must stop.
5. Resume, speak another sentence and finish.
6. Confirm local files are AAC-LC/M4A, mono 16 kHz.
7. Observe `/voice-intake/v2` progress through upload, transcription, summary, publication and readback.
8. Terminal status must be:

```text
published_verified
github_verified=true
server_audio_purged=true
```

9. Confirm local M4A files are deleted only after that terminal state.

## Binary-safe M4A extraction

Use `exec-out` with a binary-safe host process, not a text shell redirect:

```python
import subprocess
from pathlib import Path

adb = r"<absolute adb path>"
pkg = "com.onedayonemasterpiece.recordideahub.v11"
remote = "files/audio/<actual-file>.m4a"
local = Path(r"<evidence-directory>/sample.m4a")

with local.open("wb") as output:
    subprocess.run(
        [adb, "exec-out", "run-as", pkg, "cat", remote],
        stdout=output,
        check=True,
    )
```

Validate with `ffprobe`: MP4/M4A container, AAC-LC, 16 kHz, mono and approximately 32 kbit/s.

## Diagnostics

Before reproduction:

```bash
adb logcat -c
PID="$(adb shell pidof -s com.onedayonemasterpiece.recordideahub.v11)"
adb logcat --pid="$PID"
```

Useful evidence:

```bash
adb shell dumpsys activity services com.onedayonemasterpiece.recordideahub.v11
adb shell dumpsys thermalservice
adb shell run-as com.onedayonemasterpiece.recordideahub.v11 ls -la files/audio databases shared_prefs
```

Record:

- exact app/main commit and APK SHA;
- device model, Android/One UI and build fingerprint;
- `session_id`;
- state transition where the defect occurred;
- safe typed backend error/status;
- M4A metadata and file size;
- IdeaHub commit/source path;
- whether server/local purge occurred.

Never include device token, provider keys, audio bytes or full personal transcript in diagnostic reports.

## Known non-blocking observations

- A warm marker on the adaptive icon may be partially clipped by one OEM launcher mask.
- Samsung may log `MPEG4Writer: Stop() called but track is not started or stopped` without crash or invalid M4A.

Do not reopen the implementation batch for these alone. Create a focused follow-up only when the visual polish is prioritized or the media warning correlates with an actual corrupt/lost segment.
