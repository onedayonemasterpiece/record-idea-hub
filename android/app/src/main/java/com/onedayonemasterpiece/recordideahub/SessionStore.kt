package com.onedayonemasterpiece.recordideahub

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class SessionStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                protocol_version INTEGER NOT NULL DEFAULT 2,
                started_at TEXT NOT NULL,
                ended_at TEXT,
                timezone TEXT NOT NULL,
                device_label TEXT NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                wall_elapsed_ms INTEGER NOT NULL DEFAULT 0,
                manual_pause_ms INTEGER NOT NULL DEFAULT 0,
                auto_silence_skipped_ms INTEGER NOT NULL DEFAULT 0,
                chunk_count INTEGER NOT NULL DEFAULT 0,
                capture_state TEXT NOT NULL,
                capture_activity TEXT NOT NULL DEFAULT 'idle',
                capture_policy TEXT NOT NULL DEFAULT 'voice_activity_auto_pause_v1',
                vad_engine TEXT,
                remote_state TEXT NOT NULL DEFAULT 'local_only',
                server_initialized INTEGER NOT NULL DEFAULT 0,
                complete_sent INTEGER NOT NULL DEFAULT 0,
                poll_count INTEGER NOT NULL DEFAULT 0,
                chunks_uploaded INTEGER NOT NULL DEFAULT 0,
                chunks_transcribed INTEGER NOT NULL DEFAULT 0,
                github_url TEXT,
                github_commit_sha TEXT,
                last_error TEXT,
                retry_at_epoch_ms INTEGER,
                manual_pause_started_epoch_ms INTEGER,
                audio_deleted INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE chunks (
                session_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                wall_start_ms INTEGER NOT NULL DEFAULT 0,
                wall_end_ms INTEGER NOT NULL DEFAULT 0,
                path TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                mime_type TEXT NOT NULL DEFAULT 'audio/mp4',
                uploaded INTEGER NOT NULL DEFAULT 0,
                transcript_json TEXT,
                PRIMARY KEY (session_id, chunk_index),
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_sessions_sync ON sessions(remote_state, capture_state, created_at)")
        db.execSQL("CREATE INDEX idx_chunks_upload ON chunks(session_id, uploaded, chunk_index)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN retry_at_epoch_ms INTEGER")
            db.execSQL("ALTER TABLE chunks ADD COLUMN transcript_json TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE sessions ADD COLUMN wall_elapsed_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN manual_pause_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN auto_silence_skipped_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN capture_activity TEXT NOT NULL DEFAULT 'idle'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN capture_policy TEXT NOT NULL DEFAULT 'continuous_v1'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN vad_engine TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN server_initialized INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN complete_sent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN poll_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN manual_pause_started_epoch_ms INTEGER")
            db.execSQL("ALTER TABLE chunks ADD COLUMN wall_start_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chunks ADD COLUMN wall_end_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chunks ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'audio/wav'")
            db.execSQL("DROP INDEX IF EXISTS idx_chunks_upload")
            db.execSQL("CREATE INDEX idx_chunks_upload ON chunks(session_id, uploaded, chunk_index)")
        }
    }

    @Synchronized
    fun createSession(
        sessionId: String,
        startedAt: String,
        timezone: String,
        deviceLabel: String,
        protocolVersion: Int = 2,
        capturePolicy: String = CapturePolicy.VOICE_ACTIVITY_AUTO_PAUSE_V1,
        vadEngine: String? = EfficientVad.ENGINE_NAME,
    ) {
        writableDatabase.insertOrThrow(
            "sessions", null, ContentValues().apply {
                put("session_id", sessionId)
                put("protocol_version", protocolVersion)
                put("started_at", startedAt)
                put("timezone", timezone)
                put("device_label", deviceLabel)
                put("capture_state", CaptureState.RECORDING)
                put("capture_activity", if (capturePolicy == CapturePolicy.CONTINUOUS_V1) {
                    CaptureActivity.VOICE
                } else {
                    CaptureActivity.AUTO_SILENCE
                })
                put("capture_policy", capturePolicy)
                if (vadEngine == null) putNull("vad_engine") else put("vad_engine", vadEngine)
                put("remote_state", RemoteState.LOCAL_ONLY)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    @Synchronized
    fun activeSession(): SessionSnapshot? = queryOne(
        "capture_state IN (?, ?)", arrayOf(CaptureState.RECORDING, CaptureState.PAUSED), "created_at DESC",
    )

    @Synchronized
    fun latestSession(): SessionSnapshot? = queryOne(
        "capture_state != ?", arrayOf(CaptureState.DISCARDED), "created_at DESC",
    )

    @Synchronized
    fun session(sessionId: String): SessionSnapshot? = queryOne(
        "session_id = ?", arrayOf(sessionId), "created_at DESC",
    )

    private fun queryOne(selection: String, args: Array<String>, order: String): SessionSnapshot? {
        readableDatabase.query(
            "sessions", SESSION_COLUMNS, selection, args, null, null, order, "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toSession() else null }
    }

    @Synchronized
    fun setCaptureState(sessionId: String, state: String, activity: String? = null) {
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("capture_state", state)
                if (activity != null) put("capture_activity", activity)
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun markCaptureFallbackContinuous(sessionId: String): Boolean =
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("capture_policy", CapturePolicy.CONTINUOUS_V1)
                putNull("vad_engine")
                put("capture_activity", CaptureActivity.FALLBACK_CONTINUOUS)
            },
            "session_id=? AND protocol_version>=2 AND server_initialized=0 AND complete_sent=0",
            arrayOf(sessionId),
        ) == 1

    @Synchronized
    fun updateCaptureProgress(
        sessionId: String,
        durationMs: Long,
        wallElapsedMs: Long,
        manualPauseMs: Long,
        autoSilenceSkippedMs: Long,
        activity: String,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "sessions", ContentValues().apply {
                    put("duration_ms", durationMs.coerceAtLeast(0L))
                    put("wall_elapsed_ms", wallElapsedMs.coerceAtLeast(0L))
                    put("manual_pause_ms", manualPauseMs.coerceAtLeast(0L))
                    put("auto_silence_skipped_ms", autoSilenceSkippedMs.coerceAtLeast(0L))
                    put("capture_activity", activity)
                }, "session_id=?", arrayOf(sessionId),
            )
            if (activity == CaptureActivity.FALLBACK_CONTINUOUS) {
                db.update(
                    "sessions",
                    ContentValues().apply {
                        put("capture_policy", CapturePolicy.CONTINUOUS_V1)
                        putNull("vad_engine")
                    },
                    "session_id=? AND protocol_version>=2 AND server_initialized=0 AND complete_sent=0",
                    arrayOf(sessionId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun beginManualPause(sessionId: String) {
        writableDatabase.execSQL(
            """
            UPDATE sessions SET capture_state=?, capture_activity=?,
                manual_pause_started_epoch_ms=COALESCE(manual_pause_started_epoch_ms, ?)
            WHERE session_id=?
            """.trimIndent(),
            arrayOf<Any?>(CaptureState.PAUSED, CaptureActivity.MANUAL_PAUSE, System.currentTimeMillis(), sessionId),
        )
    }

    @Synchronized
    fun endManualPause(sessionId: String) {
        val db = writableDatabase
        val pauseStarted = db.rawQuery(
            "SELECT manual_pause_started_epoch_ms FROM sessions WHERE session_id=?", arrayOf(sessionId),
        ).use { cursor -> if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0) }
        val additional = pauseStarted?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
        db.execSQL(
            """
            UPDATE sessions SET manual_pause_ms=manual_pause_ms+?, manual_pause_started_epoch_ms=NULL,
                capture_state=?,
                capture_activity=CASE WHEN capture_policy=? THEN ? ELSE ? END,
                remote_state=CASE WHEN protocol_version>=2 AND server_initialized=0 THEN ? ELSE remote_state END,
                last_error=CASE WHEN protocol_version>=2 AND server_initialized=0 THEN NULL ELSE last_error END,
                retry_at_epoch_ms=CASE WHEN protocol_version>=2 AND server_initialized=0 THEN NULL ELSE retry_at_epoch_ms END
            WHERE session_id=?
            """.trimIndent(),
            arrayOf<Any?>(
                additional,
                CaptureState.RECORDING,
                CapturePolicy.CONTINUOUS_V1,
                CaptureActivity.VOICE,
                CaptureActivity.AUTO_SILENCE,
                RemoteState.LOCAL_ONLY,
                sessionId,
            ),
        )
    }

    @Synchronized
    fun markInterruptedRecordingsPaused() {
        writableDatabase.execSQL(
            """
            UPDATE sessions SET capture_state=?, capture_activity=?,
                manual_pause_started_epoch_ms=COALESCE(manual_pause_started_epoch_ms, ?)
            WHERE capture_state=?
            """.trimIndent(),
            arrayOf<Any?>(
                CaptureState.PAUSED, CaptureActivity.MANUAL_PAUSE,
                System.currentTimeMillis(), CaptureState.RECORDING,
            ),
        )
    }

    @Synchronized
    fun nextChunkIndex(sessionId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(chunk_index), -1) + 1 FROM chunks WHERE session_id=?", arrayOf(sessionId),
        ).use { cursor -> cursor.moveToFirst(); return cursor.getInt(0) }
    }

    @Synchronized
    fun addChunk(
        sessionId: String,
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        wallStartMs: Long,
        wallEndMs: Long,
        path: String,
        sha256: String,
        mimeType: String,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "chunks", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("chunk_index", chunkIndex)
                    put("start_ms", startMs)
                    put("end_ms", endMs)
                    put("wall_start_ms", wallStartMs)
                    put("wall_end_ms", wallEndMs)
                    put("path", path)
                    put("sha256", sha256)
                    put("mime_type", mimeType)
                    put("uploaded", 0)
                    putNull("transcript_json")
                }, SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.execSQL(
                """
                UPDATE sessions SET chunk_count=(SELECT COUNT(*) FROM chunks WHERE session_id=?),
                    duration_ms=MAX(duration_ms, ?) WHERE session_id=?
                """.trimIndent(), arrayOf<Any?>(sessionId, endMs, sessionId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun finishSession(sessionId: String, endedAt: String, wallElapsedMs: Long, autoSilenceSkippedMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "sessions", ContentValues().apply {
                    put("ended_at", endedAt)
                    put("wall_elapsed_ms", wallElapsedMs.coerceAtLeast(0L))
                    put("auto_silence_skipped_ms", autoSilenceSkippedMs.coerceAtLeast(0L))
                    put("capture_state", CaptureState.FINISHED)
                    put("capture_activity", CaptureActivity.IDLE)
                    putNull("manual_pause_started_epoch_ms")
                }, "session_id=?", arrayOf(sessionId),
            )
            db.execSQL(
                """
                UPDATE sessions SET remote_state=?,last_error=NULL,retry_at_epoch_ms=NULL
                WHERE session_id=? AND protocol_version>=2 AND server_initialized=0
                """.trimIndent(),
                arrayOf<Any?>(RemoteState.LOCAL_ONLY, sessionId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun chunks(sessionId: String): List<ChunkRecord> {
        readableDatabase.query(
            "chunks", CHUNK_COLUMNS, "session_id=?", arrayOf(sessionId), null, null, "chunk_index ASC",
        ).use { cursor -> return buildList { while (cursor.moveToNext()) add(cursor.toChunk()) } }
    }

    @Synchronized
    fun pendingChunks(session: SessionSnapshot): List<ChunkRecord> = chunks(session.sessionId).filter { chunk ->
        if (session.protocolVersion >= 2) !chunk.uploaded else !chunk.transcribed
    }

    @Synchronized
    fun markChunkUploaded(sessionId: String, chunkIndex: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.update(
                "chunks", ContentValues().apply { put("uploaded", 1) },
                "session_id=? AND chunk_index=?", arrayOf(sessionId, chunkIndex.toString()),
            ) == 1) { "chunk disappeared before upload receipt persistence" }
            refreshChunkCounters(db, sessionId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun markAllV2ChunksPending(sessionId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "chunks",
                ContentValues().apply { put("uploaded", 0) },
                "session_id=?",
                arrayOf(sessionId),
            )
            db.update(
                "sessions",
                ContentValues().apply {
                    put("chunks_uploaded", 0)
                    put("complete_sent", 0)
                    put("poll_count", 0)
                },
                "session_id=? AND protocol_version>=2",
                arrayOf(sessionId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun saveChunkTranscript(sessionId: String, chunkIndex: Int, result: ChunkTranscriptResult) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.update(
                "chunks", ContentValues().apply {
                    put("uploaded", 1)
                    put("transcript_json", result.transcriptJson)
                }, "session_id=? AND chunk_index=?", arrayOf(sessionId, chunkIndex.toString()),
            ) == 1) { "chunk disappeared before transcript persistence" }
            refreshChunkCounters(db, sessionId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun refreshChunkCounters(db: SQLiteDatabase, sessionId: String) {
        db.execSQL(
            """
            UPDATE sessions SET
                chunks_uploaded=(SELECT COUNT(*) FROM chunks WHERE session_id=? AND uploaded=1),
                chunks_transcribed=(SELECT COUNT(*) FROM chunks WHERE session_id=? AND transcript_json IS NOT NULL),
                retry_at_epoch_ms=NULL, last_error=NULL WHERE session_id=?
            """.trimIndent(), arrayOf<Any?>(sessionId, sessionId, sessionId),
        )
    }

    @Synchronized
    fun sessionsNeedingSync(nowEpochMs: Long = System.currentTimeMillis()): List<SessionSnapshot> {
        readableDatabase.query(
            "sessions",
            SESSION_COLUMNS,
            """
            ((protocol_version>=2 AND capture_state=?) OR
             (protocol_version<2 AND capture_state IN (?, ?)))
            AND remote_state NOT IN (?, ?)
            AND (retry_at_epoch_ms IS NULL OR retry_at_epoch_ms<=?)
            """.trimIndent(),
            arrayOf(
                CaptureState.FINISHED,
                CaptureState.PAUSED,
                CaptureState.FINISHED,
                RemoteState.PUBLISHED_VERIFIED,
                RemoteState.RECONCILIATION_REQUIRED,
                nowEpochMs.toString(),
            ),
            null,
            null,
            "created_at ASC",
        ).use { cursor -> return buildList { while (cursor.moveToNext()) add(cursor.toSession()) } }
    }

    @Synchronized
    fun markServerInitialized(sessionId: String) {
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("server_initialized", 1)
                put("remote_state", RemoteState.RECEIVING)
                putNull("last_error")
                putNull("retry_at_epoch_ms")
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun markCompleteSent(sessionId: String) {
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("complete_sent", 1)
                put("remote_state", RemoteState.QUEUED)
                put("poll_count", 0)
                putNull("retry_at_epoch_ms")
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun nextPollDelaySeconds(sessionId: String): Long {
        val db = writableDatabase
        val current = db.rawQuery("SELECT poll_count FROM sessions WHERE session_id=?", arrayOf(sessionId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val next = (current + 1).coerceAtMost(100)
        val delay = VoiceIntakeV2Policy.pollDelaySeconds(next)
        db.update(
            "sessions",
            ContentValues().apply {
                put("poll_count", next)
                put("retry_at_epoch_ms", System.currentTimeMillis() + delay * 1000L)
            },
            "session_id=?",
            arrayOf(sessionId),
        )
        return delay
    }

    @Synchronized
    fun updateRemoteProgress(sessionId: String, progress: RemoteProgress) {
        val previousState = readableDatabase.rawQuery(
            "SELECT remote_state FROM sessions WHERE session_id=?",
            arrayOf(sessionId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("remote_state", progress.state)
                put("chunks_uploaded", progress.chunksUploaded.coerceAtLeast(0))
                if (progress.chunksTranscribed > 0 || progress.transcriptionComplete) {
                    put("chunks_transcribed", progress.chunksTranscribed)
                }
                if (progress.githubUrl == null) putNull("github_url") else put("github_url", progress.githubUrl)
                if (progress.githubCommitSha == null) putNull("github_commit_sha") else {
                    put("github_commit_sha", progress.githubCommitSha)
                }
                val message = progress.lastError ?: progress.errorCode
                if (message == null) putNull("last_error") else put("last_error", message)
                putNull("retry_at_epoch_ms")
                if (previousState != progress.state || progress.githubVerified) put("poll_count", 0)
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun setRemoteState(sessionId: String, state: String, message: String? = null) {
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("remote_state", state)
                if (message == null) putNull("last_error") else put("last_error", message)
                putNull("retry_at_epoch_ms")
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun setRetryableError(sessionId: String, delaySeconds: Long, message: String?) {
        val delay = delaySeconds.coerceAtLeast(1L)
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("remote_state", RemoteState.RETRYABLE_ERROR)
                put("retry_at_epoch_ms", System.currentTimeMillis() + delay * 1000L)
                if (message == null) putNull("last_error") else put("last_error", message)
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun setQuotaWait(sessionId: String, retryAfterSeconds: Long, message: String) {
        val delay = retryAfterSeconds.coerceAtLeast(1L)
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("remote_state", RemoteState.WAITING_FOR_QUOTA)
                put("retry_at_epoch_ms", System.currentTimeMillis() + delay * 1000L)
                put("last_error", message)
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun setLocalError(sessionId: String, message: String?) {
        writableDatabase.update(
            "sessions", ContentValues().apply {
                put("remote_state", RemoteState.RETRYABLE_ERROR)
                putNull("retry_at_epoch_ms")
                if (message == null) putNull("last_error") else put("last_error", message)
            }, "session_id=?", arrayOf(sessionId),
        )
    }

    @Synchronized
    fun discardSession(sessionId: String) {
        chunks(sessionId).forEach { if (it.path.isNotBlank()) File(it.path).delete() }
        writableDatabase.delete("sessions", "session_id=?", arrayOf(sessionId))
    }

    @Synchronized
    fun deleteAllVerifiedAudio(): Boolean {
        val ids = readableDatabase.query(
            "sessions",
            arrayOf("session_id"),
            "remote_state=? AND audio_deleted=0",
            arrayOf(RemoteState.PUBLISHED_VERIFIED),
            null,
            null,
            "created_at ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        var allDeleted = true
        for (sessionId in ids) {
            if (!deleteVerifiedAudio(sessionId)) allDeleted = false
        }
        return allDeleted
    }

    @Synchronized
    fun deleteVerifiedAudio(sessionId: String): Boolean {
        val failed = chunks(sessionId).filter { record ->
            if (record.path.isBlank()) return@filter false
            val file = File(record.path)
            file.exists() && !file.delete()
        }
        if (failed.isNotEmpty()) {
            writableDatabase.update(
                "sessions",
                ContentValues().apply {
                    put("remote_state", RemoteState.PUBLISHED_VERIFIED)
                    put("last_error", "Сервер и GitHub подтверждены; локальное аудио будет удалено повторно")
                    put("audio_deleted", 0)
                    putNull("retry_at_epoch_ms")
                },
                "session_id=?",
                arrayOf(sessionId),
            )
            return false
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("chunks", ContentValues().apply { put("path", "") }, "session_id=?", arrayOf(sessionId))
            db.update(
                "sessions", ContentValues().apply {
                    put("audio_deleted", 1)
                    put("remote_state", RemoteState.PUBLISHED_VERIFIED)
                    putNull("last_error")
                    putNull("retry_at_epoch_ms")
                }, "session_id=?", arrayOf(sessionId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    private fun Cursor.toSession() = SessionSnapshot(
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        protocolVersion = getInt(getColumnIndexOrThrow("protocol_version")),
        startedAt = getString(getColumnIndexOrThrow("started_at")),
        endedAt = nullableString("ended_at"),
        timezone = getString(getColumnIndexOrThrow("timezone")),
        deviceLabel = getString(getColumnIndexOrThrow("device_label")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        wallElapsedMs = getLong(getColumnIndexOrThrow("wall_elapsed_ms")),
        manualPauseMs = getLong(getColumnIndexOrThrow("manual_pause_ms")),
        autoSilenceSkippedMs = getLong(getColumnIndexOrThrow("auto_silence_skipped_ms")),
        chunkCount = getInt(getColumnIndexOrThrow("chunk_count")),
        captureState = getString(getColumnIndexOrThrow("capture_state")),
        captureActivity = getString(getColumnIndexOrThrow("capture_activity")),
        capturePolicy = getString(getColumnIndexOrThrow("capture_policy")),
        vadEngine = nullableString("vad_engine"),
        remoteState = getString(getColumnIndexOrThrow("remote_state")),
        serverInitialized = getInt(getColumnIndexOrThrow("server_initialized")) != 0,
        completeSent = getInt(getColumnIndexOrThrow("complete_sent")) != 0,
        pollCount = getInt(getColumnIndexOrThrow("poll_count")),
        chunksUploaded = getInt(getColumnIndexOrThrow("chunks_uploaded")),
        chunksTranscribed = getInt(getColumnIndexOrThrow("chunks_transcribed")),
        githubUrl = nullableString("github_url"),
        githubCommitSha = nullableString("github_commit_sha"),
        lastError = nullableString("last_error"),
        retryAtEpochMs = nullableLong("retry_at_epoch_ms"),
    )

    private fun Cursor.toChunk() = ChunkRecord(
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        chunkIndex = getInt(getColumnIndexOrThrow("chunk_index")),
        startMs = getLong(getColumnIndexOrThrow("start_ms")),
        endMs = getLong(getColumnIndexOrThrow("end_ms")),
        wallStartMs = getLong(getColumnIndexOrThrow("wall_start_ms")),
        wallEndMs = getLong(getColumnIndexOrThrow("wall_end_ms")),
        path = getString(getColumnIndexOrThrow("path")),
        sha256 = getString(getColumnIndexOrThrow("sha256")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        uploaded = getInt(getColumnIndexOrThrow("uploaded")) != 0,
        transcriptJson = nullableString("transcript_json"),
    )

    private fun Cursor.nullableString(name: String): String? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableLong(name: String): Long? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private const val DB_NAME = "record-idea-hub.sqlite3"
        private const val DB_VERSION = 3
        private val SESSION_COLUMNS = arrayOf(
            "session_id", "protocol_version", "started_at", "ended_at", "timezone", "device_label",
            "duration_ms", "wall_elapsed_ms", "manual_pause_ms", "auto_silence_skipped_ms",
            "chunk_count", "capture_state", "capture_activity", "capture_policy", "vad_engine",
            "remote_state", "server_initialized", "complete_sent", "poll_count", "chunks_uploaded",
            "chunks_transcribed", "github_url", "github_commit_sha", "last_error", "retry_at_epoch_ms",
        )
        private val CHUNK_COLUMNS = arrayOf(
            "session_id", "chunk_index", "start_ms", "end_ms", "wall_start_ms", "wall_end_ms",
            "path", "sha256", "mime_type", "uploaded", "transcript_json",
        )
    }
}
