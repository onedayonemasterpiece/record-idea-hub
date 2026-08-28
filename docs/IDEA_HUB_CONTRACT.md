# IdeaHub intake contract

The application does not create a parallel ad-hoc inbox. `my-data-hub` publishes each completed session through the authoritative IdeaHub intake registry.

For session `voice-YYYYMMDD-HHMMSS-xxxxxxxx`, one non-force atomic Git transaction:

```text
creates  inbox/voice/YYYY/MM/<session_id>.md
creates  registry/sessions/YYYY/MM/<session_id>.md
updates  registry/intake-sessions.yaml
updates  inbox/voice/README.md
```

The README update keeps the chronological voice-inbox index discoverable without requiring later agents to inspect Git history.

## Source packet

The source packet contains:

- full ordered transcript;
- short and detailed summaries;
- theses, ideas, decisions, tasks and facts;
- entities, related projects, questions, contradictions and uncertainties;
- Android/client/API version;
- capture policy and AAC/M4A transport profile;
- wall elapsed, manual pause, recorded audio and automatically skipped silence durations;
- optional VAD engine/version/config provenance;
- transport-segment hashes;
- Gemini request UIDs and non-secret limiter evidence;
- pinned terminology snapshot evidence;
- a prominent notice that the session is new and not yet materialized into owning canonical artifacts.

Audio is never committed.

## Session detail

The session detail records source provenance, device-local origin, capture interval, source-packet route and the next product step. Its front matter remains compatible with the IdeaHub validator:

```yaml
session_id: <session_id>
overall_status: open
```

## Registry entry

The registry entry is intentionally open:

```yaml
status:
  overall: open
  capture: complete
  transcription: complete
  normalization: complete
  routing: pending
  processing: pending
  materialization: pending
  verification: complete
counts:
  unit: sessions
  observed: 1
  transcribed: 1
  normalized: 1
  routed: 0
  processed: 0
  excluded: 0
  pending: 1
```

`processing_completed_at` is absent while processing remains pending. The updated registry is validated against the current `schemas/intake-session.schema.json` before publication.

## Atomicity and idempotency

- The current `main` commit and tree are read first.
- All source/detail/index/registry changes belong to one new tree and one neutral commit.
- `refs/heads/main` is updated without force.
- Normal concurrent branch movement causes a fresh read and bounded retry.
- A lost GitHub response is reconciled by deterministic session paths and `session_id`.
- Success is returned only after exact-commit and current-main readback.
- Server audio is purged only after readback; Android waits for the corresponding terminal status before deleting local M4A.

The commit message is:

```text
intake(voice): register <session_id>
```

Dictated content is never copied into the commit message, PR description, issue, discussion or operational log. Every unprocessed recording is visible through both `registry/intake-sessions.yaml` and the chronological `inbox/voice/README.md` index.
