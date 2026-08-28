# IdeaHub intake contract

The application does not create a parallel ad-hoc inbox. `my-data-hub` publishes each completed session through the existing authoritative IdeaHub registry contract.

For session `voice-YYYYMMDD-HHMMSS-xxxxxxxx`, one non-force Git transaction creates or updates exactly three paths:

```text
inbox/voice/YYYY/MM/<session_id>.md
registry/sessions/YYYY/MM/<session_id>.md
registry/intake-sessions.yaml
```

## Source packet

The source packet contains:

- exact chunk-ordered transcript;
- short and detailed summaries;
- theses, ideas, decisions, tasks and facts;
- entities, related projects, questions, contradictions and uncertainties;
- model, prompt-version, chunk hash and retention evidence;
- a prominent notice that the session is new and not yet materialized into owning canonical artifacts.

Audio is never committed.

## Session detail

The session detail records source provenance, device-local origin, capture interval, source-packet route and the next product step. Its front matter is compatible with the current IdeaHub validator:

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

`processing_completed_at` is absent while processing is pending. The existing registry document is validated against the current `schemas/intake-session.schema.json` before the branch ref is updated.

## Atomicity and idempotency

- The current `main` commit and tree are read first.
- All three blobs are attached to one new tree and one neutral commit.
- `refs/heads/main` is updated without force.
- Normal concurrent movement causes a fresh read and bounded retry.
- A lost HTTP response is reconciled by the deterministic source path and `session_id`.
- Success is returned only after exact-commit and current-main readback.

The commit message is:

```text
intake(voice): register <session_id>
```

Dictated content is never copied into the commit message, PR description, issue, discussion or log. This makes every unprocessed recording visible to later agents through `registry/intake-sessions.yaml` without requiring them to inspect Git history.
