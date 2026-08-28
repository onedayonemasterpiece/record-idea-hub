package com.onedayonemasterpiece.recordideahub

/**
 * Reconciles the local upload ledger with the aggregate durable v2 server receipt count.
 *
 * The create/status contract exposes a count, not per-index receipt identities. Therefore a count
 * must never be used to guess that the first N local files are acknowledged. Missing local flags
 * are harmless because duplicate chunk PUTs are idempotent; a server count lower than the number
 * of locally acknowledged chunks means the local flags are no longer authoritative and all chunks
 * must be offered again.
 *
 * A completed server session is stronger evidence: its accepted manifest is contiguous and closed.
 * In that case the server count must equal the local durable chunk count before Android can trust it.
 */
fun SessionStore.reconcileV2ServerState(
    sessionId: String,
    serverChunksReceived: Int,
    serverRecordingFinished: Boolean,
): Boolean = synchronized(this) {
    require(serverChunksReceived >= 0) { "negative server chunk count" }
    val db = writableDatabase
    db.beginTransaction()
    try {
        val counts = db.rawQuery(
            """
            SELECT COUNT(*) AS local_count,
                   COALESCE(SUM(CASE WHEN uploaded=1 THEN 1 ELSE 0 END), 0) AS local_uploaded
            FROM chunks WHERE session_id=?
            """.trimIndent(),
            arrayOf(sessionId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "local session disappeared during reconciliation" }
            cursor.getInt(0) to cursor.getInt(1)
        }
        val (localCount, localUploaded) = counts
        if (serverChunksReceived > localCount) return@synchronized false
        if (serverRecordingFinished && serverChunksReceived != localCount) return@synchronized false

        if (serverRecordingFinished) {
            db.update(
                "chunks",
                android.content.ContentValues().apply { put("uploaded", 1) },
                "session_id=?",
                arrayOf(sessionId),
            )
        } else if (serverChunksReceived < localUploaded) {
            // The server durable ledger is authoritative. Re-offer all local segments; unchanged
            // duplicates are safe and this avoids pretending that an aggregate count identifies
            // particular indices.
            db.update(
                "chunks",
                android.content.ContentValues().apply { put("uploaded", 0) },
                "session_id=?",
                arrayOf(sessionId),
            )
        }

        db.update(
            "sessions",
            android.content.ContentValues().apply {
                put("chunks_uploaded", serverChunksReceived)
                put("complete_sent", if (serverRecordingFinished) 1 else 0)
                if (!serverRecordingFinished) put("poll_count", 0)
            },
            "session_id=?",
            arrayOf(sessionId),
        )
        db.setTransactionSuccessful()
        true
    } finally {
        db.endTransaction()
    }
}
