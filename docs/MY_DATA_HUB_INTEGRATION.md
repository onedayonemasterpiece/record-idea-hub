# my-data-hub Voice Intake integration

## Purpose

`record-idea-hub` is a local-first Android client. The existing `my-data-hub` control-plane on devstand is the only server boundary for Google API traffic, temporary processing storage and IdeaHub publication.

Current public origin:

```text
https://mcp-datahub.kenigevents.ru
```

Android 1.1 API root:

```text
/voice-intake/v2
```

The authoritative detailed contract is maintained in `my-data-hub`:

```text
docs/handoffs/record-idea-hub-android-1.1-api-contract.md
docs/operations/record-idea-hub-voice-intake-v2.md
```

The legacy `/voice-intake/v1` path remains available only for unfinished Android 1.0 WAV sessions.

## Authentication

Every request requires one high-entropy device credential:

```http
Authorization: Bearer <device token>
```

The token identifies the single owner device. It is not a Google, Supabase or GitHub credential.

## API v2

### Capabilities

```http
GET /voice-intake/v2/capabilities
```

Returns the accepted AAC-LC/M4A profile, safety bounds and the expected two-request processing path. It performs no Google request.

### Create or re-open a durable session

```http
POST /voice-intake/v2/sessions
Content-Type: application/json
```

Create is idempotent and survives process/container restart. Every Android synchronization pass repeats it before upload. Immutable metadata includes session identity, capture policy, audio profile and optional VAD provenance.

### Upload one independent M4A segment

```http
PUT /voice-intake/v2/sessions/{session_id}/chunks/{chunk_index}
Content-Type: audio/mp4
X-Chunk-SHA256: <64 lowercase hex>
X-Chunk-Duration-Ms: <positive integer>
X-Audio-Start-Ms: <compacted audio timeline>
X-Audio-End-Ms: <compacted audio timeline>
X-Wall-Start-Ms: <session wall timeline>
X-Wall-End-Ms: <session wall timeline>
```

The server validates body bounds, SHA-256, MP4 container, AAC-LC codec, 16 kHz mono profile and duration before issuing a durable receipt. Uploads, including duplicate uploads, make zero Gemini calls and return no synthetic transcript.

### Complete the logical recording

```http
POST /voice-intake/v2/sessions/{session_id}/complete
Content-Type: application/json
```

The exact manifest contains all contiguous segment indices, hashes and audio/wall timelines. The server durably closes the session and queues asynchronous processing before returning `202`.

### Read durable progress

```http
GET /voice-intake/v2/sessions/{session_id}
```

Relevant states:

```text
receiving
queued
normalizing
transcribing
summarizing
publishing
verifying
waiting_quota
retryable_error
reconciliation_required
published_verified
```

Only this conjunction is terminal success:

```text
state=published_verified
github_verified=true
server_audio_purged=true
```

## Processing contract

For the priority review scenario up to roughly 20 minutes:

1. create and all uploads: zero Gemini requests;
2. aggregate transcription: one Gemini Flash-Lite request;
3. structured detailed summary: one Gemini Flash-Lite request;
4. GitHub publication/readback/purge: zero Gemini requests.

A summary retry does not repeat a durable transcription. A GitHub retry does not repeat either Gemini stage.

## Retry and reconciliation

- Create, upload and an identical complete manifest are idempotent.
- Quota denial before send enters `waiting_quota` with an authoritative retry time.
- A known safe pre-send failure may be retried.
- A provider request whose outcome is ambiguous after send enters `reconciliation_required`; Android must not silently replay it.
- Local audio is retained through every non-terminal state.

## Secret boundary

Only devstand receives:

- the configured Google key pool;
- dedicated shared-limiter Supabase credentials;
- the bounded GitHub credential for `onedayonemasterpiece/idea-hub`;
- the device bearer token.

The APK receives only:

- the public HTTPS origin;
- the device bearer token, stored through Android Keystore-backed encryption.

## Server storage boundary

Unlike v1, v2 temporarily persists uploaded M4A segments and normalized audio in a private spool so processing can continue independently of the phone. Audio is never committed to GitHub and is deleted immediately after successful IdeaHub exact/current-main readback. Small non-audio receipts may remain for reconciliation.
