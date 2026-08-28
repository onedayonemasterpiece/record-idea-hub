# my-data-hub integration

## Purpose

`record-idea-hub` is a local Android client. The existing `my-data-hub` control-plane on devstand provides the only server boundary for Google API traffic and IdeaHub publication.

The server implementation lives in:

```text
src/my_data_hub/voice_intake/
```

Its operational runbook lives in the my-data-hub repository:

```text
docs/operations/record-idea-hub-voice-intake.md
```

## API root

```text
/voice-intake/v1
```

All requests require one high-entropy device credential:

```http
Authorization: Bearer <device token>
```

The token identifies the single owner device; it is not a Google, Supabase or GitHub credential.

## Operations

### Open/validate a client-owned session

```http
POST /voice-intake/v1/sessions
Content-Type: application/json
```

The server validates identity and metadata but does not create a durable server-side session record.

### Transcribe one WAV chunk

```http
PUT /voice-intake/v1/sessions/{session_id}/chunks/{chunk_index}
Content-Type: audio/wav
X-Chunk-SHA256: <64 lowercase hex>
X-Chunk-Duration-Ms: <positive integer>
```

The response contains the validated transcript object, model, usage and non-secret limiter evidence. Android stores the transcript in its local SQLite transaction before considering the chunk complete.

### Complete and publish

```http
POST /voice-intake/v1/sessions/{session_id}/complete
Content-Type: application/json
```

The body contains session metadata plus the complete ordered list of locally persisted transcript objects. The server performs one structured Flash-Lite synthesis and one atomic IdeaHub transaction.

### Reconcile publication

```http
GET /voice-intake/v1/sessions/{session_id}
```

Android calls this before repeating completion after an uncertain network outcome. The status is derived from deterministic paths in `idea-hub/main`, not from volatile server memory.

## Retry contract

A retryable failure uses a typed detail object:

```json
{
  "detail": {
    "code": "quota_exhausted_rpm",
    "retryable": true,
    "retry_after_seconds": 42,
    "reconciliation_required": false
  }
}
```

Android converts quota errors to `WAITING_FOR_QUOTA`, shows the concrete retry time and schedules WorkManager. It never deletes or re-records source audio because of a server failure.

## Secret boundary

Only the devstand process receives:

- Google API key pool;
- dedicated shared-limiter Supabase URL and service key;
- GitHub token with access to `onedayonemasterpiece/idea-hub`;
- the one-device bearer token.

The APK receives only:

- the public HTTPS devstand URL;
- the one-device bearer token.

The existing host `gh` authorization is used during devstand provisioning to obtain a private runtime token. The Android application neither invokes GitHub directly nor stores a GitHub credential.

## Non-goals

This integration does not introduce:

- a separate Fly application;
- another server database;
- Redis or a message broker;
- server-side audio storage;
- an MCP call in the recording hot path;
- multiple users or device synchronization.
