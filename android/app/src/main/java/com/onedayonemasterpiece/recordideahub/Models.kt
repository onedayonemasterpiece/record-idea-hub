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

object RemoteState {
    const val LOCAL_ONLY = "local_only"
    const val RECEIVING = "receiving"
    const val PROCESSING = "processing"
    const val WAITING_FOR_QUOTA = "waiting_for_quota"
    const val PUBLISHING = "publishing"
    const val PUBLISHED_VERIFIED = "published_verified"
    const val RETRYABLE_ERROR = "retryable_error"
}

data class SessionSnapshot(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String?,
    val timezone: String,
    val deviceLabel: String,
    val durationMs: Long,
    val chunkCount: Int,
    val captureState: String,
    val remoteState: String,
    val chunksUploaded: Int,
    val chunksTranscribed: Int,
    val githubUrl: String?,
    val githubCommitSha: String?,
    val lastError: String?,
)

data class ChunkRecord(
    val sessionId: String,
    val chunkIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val path: String,
    val sha256: String,
    val uploaded: Boolean,
)

data class RemoteProgress(
    val state: String,
    val recordingFinished: Boolean,
    val chunksExpected: Int?,
    val chunksUploaded: Int,
    val chunksTranscribed: Int,
    val githubVerified: Boolean,
    val githubUrl: String?,
    val githubCommitSha: String?,
    val lastError: String?,
    val retryAfterSeconds: Int?,
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
