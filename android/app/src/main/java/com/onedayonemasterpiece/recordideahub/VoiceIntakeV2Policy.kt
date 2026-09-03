package com.onedayonemasterpiece.recordideahub

import kotlin.math.ceil

internal object VoiceIntakeV2Policy {
    const val WALL_TIMELINE_OVERLAP_TOLERANCE_MS = 50L

    private val manualReconciliationCodes = setOf(
        "session_metadata_conflict",
        "chunk_conflict",
        "complete_manifest_conflict",
        "complete_manifest_mismatch",
        "chunk_sha256_mismatch",
        "session_not_receiving",
        "request_too_large",
        "session_size_limit_exceeded",
        "session_audio_limit_exceeded",
        "audio_content_type_invalid",
        "session_invalid",
        "chunk_metadata_invalid",
        "audio_invalid",
        "audio_probe_invalid",
        "audio_container_invalid",
        "audio_codec_invalid",
        "audio_format_invalid",
        "audio_duration_invalid",
        "audio_duration_mismatch",
        "complete_manifest_invalid",
        "complete_time_invalid",
        "provider_outcome_ambiguous",
        "provider_timeout",
        "provider_network_error",
        "limiter_reconciliation_required",
        "limiter_finalization_failed",
        "response_schema_invalid",
        "voice_request_exceeds_model_tpm",
        "unsupported_voice_model",
    )

    private val quotaCodes = setOf(
        "google_quota_wait",
        "provider_429",
        "quota_exhausted_rpm",
        "quota_exhausted_tpm",
        "quota_exhausted_rpd",
    )

    fun requiresManualReconciliation(code: String, serverFlag: Boolean): Boolean =
        serverFlag || code in manualReconciliationCodes

    fun isQuotaCode(code: String): Boolean = code in quotaCodes

    fun shouldRetryComplete(progress: RemoteProgress): Boolean =
        progress.state == RemoteState.RETRYABLE_ERROR &&
            progress.retryable &&
            !progress.reconciliationRequired &&
            (progress.retryAfterSeconds == null || progress.retryAfterSeconds <= 0)

    fun pollDelaySeconds(nextPollCount: Int): Long = when (nextPollCount.coerceAtLeast(1)) {
        1 -> 5L
        2 -> 15L
        3 -> 30L
        4 -> 60L
        5 -> 120L
        else -> 300L
    }

    fun retryDelaySeconds(retryAfterSeconds: Int?, fallbackSeconds: Long): Long =
        retryAfterSeconds?.takeIf { it > 0 }?.toLong() ?: fallbackSeconds.coerceAtLeast(1L)

    fun wallTimelineFollows(previousEndMs: Long, currentStartMs: Long): Boolean =
        currentStartMs + WALL_TIMELINE_OVERLAP_TOLERANCE_MS >= previousEndMs

    fun normalizeServiceBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return trimmed.replace(Regex("/voice-intake/v(?:1|2)$"), "")
    }

    fun delaySecondsUntil(epochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Long {
        val remainingMs = epochMs - nowEpochMs
        return if (remainingMs <= 0L) 0L else ceil(remainingMs / 1000.0).toLong()
    }
}
