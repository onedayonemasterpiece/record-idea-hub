package com.onedayonemasterpiece.recordideahub

/**
 * Reconciles the local upload ledger with the durable v2 server receipt count.
 *
 * This is deliberately run after every idempotent create. It covers connection loss after an
 * accepted upload and the opposite case where a server spool was restored without a receipt the
 * phone previously believed was durable. Audio remains local, so missing server receipts can be
 * safely uploaded again without repeating Gemini inference.
 */
fun SessionStore.reconcileV2ServerState(
    sessionId: String,
    serverChunksReceived: Int,
    serverRecordingFinished: Boolean,
) = synchronized(this) {
    require(serverChunksReceived >= 0) { "negative server chunk count" }
    val db = writableDatabase
    db.beginTransaction()
    try {
        val localCount = db.rawQuery(
            "SELECT COUNT(*) FROM chunks WHERE session_id=?",
            arrayOf(sessionId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "local session disappeared during reconciliation" }
            cursor.getInt(0)
        }
        check(serverChunksReceived <= localCount) {
            "server reports more chunks than the local durable ledger"
        }
        db.execSQL(
            """
            UPDATE chunks
            SET uploaded=CASE WHEN chunk_index < ? THEN 1 ELSE 0 END
            WHERE session_id=?
            """.trimIndent(),
            arrayOf<Any?>(serverChunksReceived, sessionId),
        )
        db.execSQL(
            """
            UPDATE sessions
            SET chunks_uploaded=?, complete_sent=?,
                poll_count=CASE WHEN ?=1 THEN poll_count ELSE 0 END
            WHERE session_id=?
            """.trimIndent(),
            arrayOf<Any?>(
                serverChunksReceived,
                if (serverRecordingFinished) 1 else 0,
                if (serverRecordingFinished) 1 else 0,
                sessionId,
            ),
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}
