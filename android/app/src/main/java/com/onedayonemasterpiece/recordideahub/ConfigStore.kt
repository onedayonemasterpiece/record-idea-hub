package com.onedayonemasterpiece.recordideahub

import android.content.Context

class ConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("record_idea_hub_config", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    var backendUrl: String?
        get() = preferences.getString(KEY_BACKEND_URL, null)?.trim()?.trimEnd('/')
        set(value) {
            val normalized = value?.trim()?.trimEnd('/')
            preferences.edit().putString(KEY_BACKEND_URL, normalized).apply()
        }

    var deviceToken: String?
        get() = secrets.get(KEY_DEVICE_TOKEN)
        set(value) {
            if (value.isNullOrBlank()) secrets.remove(KEY_DEVICE_TOKEN) else secrets.put(KEY_DEVICE_TOKEN, value.trim())
        }

    var autoSilenceEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_SILENCE_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTO_SILENCE_ENABLED, value).apply()
        }

    fun isConfigured(): Boolean = !backendUrl.isNullOrBlank() && !deviceToken.isNullOrBlank()

    companion object {
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_AUTO_SILENCE_ENABLED = "auto_silence_enabled"
    }
}
