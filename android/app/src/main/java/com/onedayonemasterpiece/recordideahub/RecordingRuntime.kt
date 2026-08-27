package com.onedayonemasterpiece.recordideahub

import android.content.Context

class RecordingRuntime(context: Context) {
    private val preferences = context.getSharedPreferences("record_idea_hub_runtime", Context.MODE_PRIVATE)

    fun update(sessionId: String, durationMs: Long) {
        preferences.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_DURATION_MS, durationMs)
            .apply()
    }

    fun durationFor(sessionId: String): Long? =
        if (preferences.getString(KEY_SESSION_ID, null) == sessionId) {
            preferences.getLong(KEY_DURATION_MS, 0L)
        } else {
            null
        }

    fun clear(sessionId: String) {
        if (preferences.getString(KEY_SESSION_ID, null) == sessionId) {
            preferences.edit().clear().apply()
        }
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_DURATION_MS = "duration_ms"
    }
}
