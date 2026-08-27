# IdeaHub intake contract

The app does not create a parallel ad-hoc inbox. It uses the existing IdeaHub registry contract.

For session `voice-YYYYMMDD-HHMMSS-xxxxxxxx`, one Git transaction updates two paths:

```text
registry/sessions/YYYY/MM/<session_id>.md
registry/intake-sessions.yaml
```

The detail document contains:

- exact chunk-ordered transcript;
- short and detailed summaries;
- theses, ideas, decisions, tasks and facts;
- entities, project links, questions, contradictions and uncertainties;
- model and retention evidence.

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
  unit: voice_sessions
  observed: 1
  transcribed: 1
  normalized: 1
  routed: 0
  processed: 0
  excluded: 0
  pending: 1
```

This makes unprocessed recordings visible to every later agent through the authoritative `registry/intake-sessions.yaml` queue. The generated commit message contains only the session ID, never dictated content.
