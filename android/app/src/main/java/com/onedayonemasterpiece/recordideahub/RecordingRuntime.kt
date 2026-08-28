package com.onedayonemasterpiece.recordideahub

import android.content.Context

class RecordingRuntime(context: Context) {
    private val preferences = context.getSharedPreferences("record_idea_hub_runtime", Context.MODE_PRIVATE)

    fun update(
        sessionId: String,
        durationMs: Long,
        wallElapsedMs: Long,
        autoSilenceSkippedMs: Long,
        captureActivity: String,
    ) {
        preferences.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_DURATION_MS, durationMs.coerceAtLeast(0L))
            .putLong(KEY_WALL_ELAPSED_MS, wallElapsedMs.coerceAtLeast(0L))
            .putLong(KEY_AUTO_SILENCE_SKIPPED_MS, autoSilenceSkippedMs.coerceAtLeast(0L))
            .putString(KEY_CAPTURE_ACTIVITY, captureActivity)
            .apply()
    }

    fun snapshotFor(sessionId: String): RuntimeSnapshot? =
        if (preferences.getString(KEY_SESSION_ID, null) == sessionId) {
            RuntimeSnapshot(
                durationMs = preferences.getLong(KEY_DURATION_MS, 0L),
                wallElapsedMs = preferences.getLong(KEY_WALL_ELAPSED_MS, 0L),
                autoSilenceSkippedMs = preferences.getLong(KEY_AUTO_SILENCE_SKIPPED_MS, 0L),
                captureActivity = preferences.getString(KEY_CAPTURE_ACTIVITY, CaptureActivity.IDLE)
                    ?: CaptureActivity.IDLE,
            )
        } else {
            null
        }

    fun durationFor(sessionId: String): Long? = snapshotFor(sessionId)?.durationMs

    fun clear(sessionId: String) {
        if (preferences.getString(KEY_SESSION_ID, null) == sessionId) {
            preferences.edit().clear().apply()
        }
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_WALL_ELAPSED_MS = "wall_elapsed_ms"
        private const val KEY_AUTO_SILENCE_SKIPPED_MS = "auto_silence_skipped_ms"
        private const val KEY_CAPTURE_ACTIVITY = "capture_activity"
    }
}
