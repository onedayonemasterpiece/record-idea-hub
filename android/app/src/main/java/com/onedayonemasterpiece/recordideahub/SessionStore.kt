package com.onedayonemasterpiece.recordideahub

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class SessionStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                started_at TEXT NOT NULL,
                ended_at TEXT,
                timezone TEXT NOT NULL,
                device_label TEXT NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                chunk_count INTEGER NOT NULL DEFAULT 0,
                capture_state TEXT NOT NULL,
                remote_state TEXT NOT NULL DEFAULT 'local_only',
                chunks_uploaded INTEGER NOT NULL DEFAULT 0,
                chunks_transcribed INTEGER NOT NULL DEFAULT 0,
                github_url TEXT,
                github_commit_sha TEXT,
                last_error TEXT,
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
                path TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (session_id, chunk_index),
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_sessions_sync ON sessions(remote_state, capture_state, created_at)")
        db.execSQL("CREATE INDEX idx_chunks_upload ON chunks(session_id, uploaded, chunk_index)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun createSession(sessionId: String, startedAt: String, timezone: String, deviceLabel: String) {
        writableDatabase.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("started_at", startedAt)
                put("timezone", timezone)
                put("device_label", deviceLabel)
                put("capture_state", CaptureState.RECORDING)
                put("remote_state", RemoteState.LOCAL_ONLY)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    @Synchronized
    fun activeSession(): SessionSnapshot? = queryOne(
        "capture_state IN (?, ?)",
        arrayOf(CaptureState.RECORDING, CaptureState.PAUSED),
        "created_at DESC",
    )

    @Synchronized
    fun latestSession(): SessionSnapshot? = queryOne(
        "capture_state != ?",
        arrayOf(CaptureState.DISCARDED),
        "created_at DESC",
    )

    @Synchronized
    fun session(sessionId: String): SessionSnapshot? = queryOne(
        "session_id = ?",
        arrayOf(sessionId),
        "created_at DESC",
    )

    private fun queryOne(selection: String, args: Array<String>, order: String): SessionSnapshot? {
        readableDatabase.query(
            "sessions",
            SESSION_COLUMNS,
            selection,
            args,
            null,
            null,
            order,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSession() else null
        }
    }

    @Synchronized
    fun setCaptureState(sessionId: String, state: String) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("capture_state", state) },
            "session_id=?",
            arrayOf(sessionId),
        )
    }

    @Synchronized
    fun markInterruptedRecordingsPaused() {
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("capture_state", CaptureState.PAUSED) },
            "capture_state=?",
            arrayOf(CaptureState.RECORDING),
        )
    }

    @Synchronized
    fun nextChunkIndex(sessionId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(chunk_index), -1) + 1 FROM chunks WHERE session_id=?",
            arrayOf(sessionId),
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    @Synchronized
    fun addChunk(
        sessionId: String,
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        path: String,
        sha256: String,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "chunks",
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("chunk_index", chunkIndex)
                    put("start_ms", startMs)
                    put("end_ms", endMs)
                    put("path", path)
                    put("sha256", sha256)
                    put("uploaded", 0)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.execSQL(
                """
                UPDATE sessions
                SET chunk_count=(SELECT COUNT(*) FROM chunks WHERE session_id=?),
                    duration_ms=MAX(duration_ms, ?)
                WHERE session_id=?
                """.trimIndent(),
                arrayOf(sessionId, endMs, sessionId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun finishSession(sessionId: String, endedAt: String) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("ended_at", endedAt)
                put("capture_state", CaptureState.FINISHED)
            },
            "session_id=?",
            arrayOf(sessionId),
        )
    }

    @Synchronized
    fun chunks(sessionId: String, pendingOnly: Boolean = false): List<ChunkRecord> {
        val selection = if (pendingOnly) "session_id=? AND uploaded=0" else "session_id=?"
        readableDatabase.query(
            "chunks",
            CHUNK_COLUMNS,
            selection,
            arrayOf(sessionId),
            null,
            null,
            "chunk_index ASC",
        ).use { cursor ->
            val out = mutableListOf<ChunkRecord>()
            while (cursor.moveToNext()) out += cursor.toChunk()
            return out
        }
    }

    @Synchronized
    fun markChunkUploaded(sessionId: String, chunkIndex: Int) {
        writableDatabase.update(
            "chunks",
            ContentValues().apply { put("uploaded", 1) },
            "session_id=? AND chunk_index=?",
            arrayOf(sessionId, chunkIndex.toString()),
        )
    }

    @Synchronized
    fun sessionsNeedingSync(): List<SessionSnapshot> {
        readableDatabase.query(
            "sessions",
            SESSION_COLUMNS,
            "capture_state != ? AND remote_state != ?",
            arrayOf(CaptureState.DISCARDED, RemoteState.PUBLISHED_VERIFIED),
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            val out = mutableListOf<SessionSnapshot>()
            while (cursor.moveToNext()) out += cursor.toSession()
            return out
        }
    }

    @Synchronized
    fun updateRemoteProgress(sessionId: String, progress: RemoteProgress) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("remote_state", progress.state)
                put("chunks_uploaded", progress.chunksUploaded)
                put("chunks_transcribed", progress.chunksTranscribed)
                put("github_url", progress.githubUrl)
                put("github_commit_sha", progress.githubCommitSha)
                put("last_error", progress.lastError)
            },
            "session_id=?",
            arrayOf(sessionId),
        )
    }

    @Synchronized
    fun setLocalError(sessionId: String, message: String?) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("last_error", message) },
            "session_id=?",
            arrayOf(sessionId),
        )
    }

    @Synchronized
    fun discardSession(sessionId: String) {
        chunks(sessionId).forEach { File(it.path).delete() }
        writableDatabase.delete("sessions", "session_id=?", arrayOf(sessionId))
    }

    @Synchronized
    fun deleteAllVerifiedAudio() {
        val ids = readableDatabase.query(
            "sessions",
            arrayOf("session_id"),
            "remote_state=? AND audio_deleted=0",
            arrayOf(RemoteState.PUBLISHED_VERIFIED),
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        ids.forEach(::deleteVerifiedAudio)
    }

    @Synchronized
    fun deleteVerifiedAudio(sessionId: String): Boolean {
        val records = chunks(sessionId)
        val failed = records.filter { record ->
            val file = File(record.path)
            file.exists() && !file.delete()
        }
        if (failed.isNotEmpty()) {
            setLocalError(sessionId, "GitHub подтверждён, но локальное аудио пока не удалено")
            return false
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chunks", "session_id=?", arrayOf(sessionId))
            db.update(
                "sessions",
                ContentValues().apply {
                    put("audio_deleted", 1)
                    putNull("last_error")
                },
                "session_id=?",
                arrayOf(sessionId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    private fun Cursor.toSession() = SessionSnapshot(
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        startedAt = getString(getColumnIndexOrThrow("started_at")),
        endedAt = getString(getColumnIndexOrThrow("ended_at")),
        timezone = getString(getColumnIndexOrThrow("timezone")),
        deviceLabel = getString(getColumnIndexOrThrow("device_label")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        chunkCount = getInt(getColumnIndexOrThrow("chunk_count")),
        captureState = getString(getColumnIndexOrThrow("capture_state")),
        remoteState = getString(getColumnIndexOrThrow("remote_state")),
        chunksUploaded = getInt(getColumnIndexOrThrow("chunks_uploaded")),
        chunksTranscribed = getInt(getColumnIndexOrThrow("chunks_transcribed")),
        githubUrl = getString(getColumnIndexOrThrow("github_url")),
        githubCommitSha = getString(getColumnIndexOrThrow("github_commit_sha")),
        lastError = getString(getColumnIndexOrThrow("last_error")),
    )

    private fun Cursor.toChunk() = ChunkRecord(
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        chunkIndex = getInt(getColumnIndexOrThrow("chunk_index")),
        startMs = getLong(getColumnIndexOrThrow("start_ms")),
        endMs = getLong(getColumnIndexOrThrow("end_ms")),
        path = getString(getColumnIndexOrThrow("path")),
        sha256 = getString(getColumnIndexOrThrow("sha256")),
        uploaded = getInt(getColumnIndexOrThrow("uploaded")) != 0,
    )

    companion object {
        private const val DB_NAME = "record-idea-hub.sqlite3"
        private const val DB_VERSION = 1
        private val SESSION_COLUMNS = arrayOf(
            "session_id",
            "started_at",
            "ended_at",
            "timezone",
            "device_label",
            "duration_ms",
            "chunk_count",
            "capture_state",
            "remote_state",
            "chunks_uploaded",
            "chunks_transcribed",
            "github_url",
            "github_commit_sha",
            "last_error",
        )
        private val CHUNK_COLUMNS = arrayOf(
            "session_id",
            "chunk_index",
            "start_ms",
            "end_ms",
            "path",
            "sha256",
            "uploaded",
        )
    }
}
