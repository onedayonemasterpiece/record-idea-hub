package com.onedayonemasterpiece.recordideahub

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object CaptureState {
    const val RECORDING = "recording"
    const val PAUSED = "paused"
    const val FINISHED = "finished"
    const val DISCARDED = "discarded"
}

object CaptureActivity {
    const val IDLE = "idle"
    const val VOICE = "voice"
    const val AUTO_SILENCE = "auto_silence"
    const val MANUAL_PAUSE = "manual_pause"
    const val FALLBACK_CONTINUOUS = "fallback_continuous"
}

object CapturePolicy {
    const val CONTINUOUS_V1 = "continuous_v1"
    const val VOICE_ACTIVITY_AUTO_PAUSE_V1 = "voice_activity_auto_pause_v1"
}

object RemoteState {
    const val LOCAL_ONLY = "local_only"
    const val RECEIVING = "receiving"
    const val QUEUED = "queued"
    const val NORMALIZING = "normalizing"
    const val TRANSCRIBING = "transcribing"
    const val SUMMARIZING = "summarizing"
    const val PROCESSING = "processing"
    const val WAITING_FOR_QUOTA = "waiting_quota"
    const val PUBLISHING = "publishing"
    const val VERIFYING = "verifying"
    const val RECONCILIATION_REQUIRED = "reconciliation_required"
    const val PUBLISHED_VERIFIED = "published_verified"
    const val RETRYABLE_ERROR = "retryable_error"
}

object AudioProfile {
    const val MIME_M4A = "audio/mp4"
    const val CONTAINER = "mp4"
    const val CODEC = "aac_lc"
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val BITRATE_BPS = 32_000
}

data class SessionSnapshot(
    val sessionId: String,
    val protocolVersion: Int,
    val startedAt: String,
    val endedAt: String?,
    val timezone: String,
    val deviceLabel: String,
    val durationMs: Long,
    val wallElapsedMs: Long,
    val manualPauseMs: Long,
    val autoSilenceSkippedMs: Long,
    val chunkCount: Int,
    val captureState: String,
    val captureActivity: String,
    val capturePolicy: String,
    val vadEngine: String?,
    val remoteState: String,
    val serverInitialized: Boolean,
    val completeSent: Boolean,
    val pollCount: Int,
    val chunksUploaded: Int,
    val chunksTranscribed: Int,
    val githubUrl: String?,
    val githubCommitSha: String?,
    val lastError: String?,
    val retryAtEpochMs: Long?,
    val githubVerified: Boolean = false,
    val serverAudioPurged: Boolean = false,
)

data class ChunkRecord(
    val sessionId: String,
    val chunkIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val wallStartMs: Long,
    val wallEndMs: Long,
    val path: String,
    val sha256: String,
    val mimeType: String,
    val uploaded: Boolean,
    val transcriptJson: String?,
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    val transcribed: Boolean
        get() = !transcriptJson.isNullOrBlank()
}

data class ChunkTranscriptResult(
    val transcriptJson: String,
    val model: String,
    val requestUid: String,
)

data class ChunkUploadReceipt(
    val chunkIndex: Int,
    val accepted: Boolean,
    val duplicate: Boolean,
    val chunksReceived: Int,
    val bytesReceived: Long,
)

data class RemoteProgress(
    val state: String,
    val recordingFinished: Boolean,
    val chunksExpected: Int?,
    val chunksUploaded: Int,
    val chunksTranscribed: Int,
    val geminiRequestsTotal: Int,
    val geminiRequestsCompleted: Int,
    val transcriptionComplete: Boolean,
    val summaryComplete: Boolean,
    val githubVerified: Boolean,
    val serverAudioPurged: Boolean,
    val githubUrl: String?,
    val githubCommitSha: String?,
    val lastError: String?,
    val errorCode: String?,
    val retryable: Boolean,
    val retryAfterSeconds: Int?,
    val reconciliationRequired: Boolean,
)

data class RuntimeSnapshot(
    val durationMs: Long,
    val wallElapsedMs: Long,
    val autoSilenceSkippedMs: Long,
    val captureActivity: String,
)

fun newSessionId(now: OffsetDateTime = OffsetDateTime.now()): String {
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US))
    val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
    return "voice-$stamp-$suffix".lowercase(Locale.US)
}

fun currentTimezone(): String = ZoneId.systemDefault().id

fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainder)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, remainder)
    }
}
