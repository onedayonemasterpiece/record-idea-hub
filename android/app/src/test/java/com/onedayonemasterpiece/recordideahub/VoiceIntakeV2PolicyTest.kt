package com.onedayonemasterpiece.recordideahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIntakeV2PolicyTest {
    @Test
    fun normalizesHostAndVersionedApiRoots() {
        assertEquals(
            "https://mcp-datahub.kenigevents.ru",
            VoiceIntakeV2Policy.normalizeServiceBaseUrl("https://mcp-datahub.kenigevents.ru/"),
        )
        assertEquals(
            "https://mcp-datahub.kenigevents.ru",
            VoiceIntakeV2Policy.normalizeServiceBaseUrl(
                "https://mcp-datahub.kenigevents.ru/voice-intake/v2",
            ),
        )
        assertEquals(
            "https://example.test/prefix",
            VoiceIntakeV2Policy.normalizeServiceBaseUrl(
                "https://example.test/prefix/voice-intake/v1/",
            ),
        )
    }

    @Test
    fun pollingBackoffIsBoundedAndNotFrequent() {
        assertEquals(
            listOf(5L, 15L, 30L, 60L, 120L, 300L, 300L),
            (1..7).map(VoiceIntakeV2Policy::pollDelaySeconds),
        )
    }

    @Test
    fun completeRetryWaitsForServerRetryAtAndNeverCrossesReconciliation() {
        val ready = progress(retryable = true, retryAfterSeconds = null)
        val waiting = progress(retryable = true, retryAfterSeconds = 45)
        val ambiguous = progress(
            retryable = true,
            retryAfterSeconds = null,
            reconciliationRequired = true,
        )
        assertFalse(VoiceIntakeV2Policy.shouldRetryComplete(ready))
        assertTrue(VoiceIntakeV2Policy.shouldRetryComplete(ready.copy(recordingFinished = false)))
        assertFalse(VoiceIntakeV2Policy.shouldRetryComplete(waiting))
        assertFalse(VoiceIntakeV2Policy.shouldRetryComplete(ambiguous))
    }

    @Test
    fun typedConflictsAndAmbiguousProviderOutcomesRequireManualReconciliation() {
        assertTrue(VoiceIntakeV2Policy.requiresManualReconciliation("chunk_conflict", false))
        assertTrue(VoiceIntakeV2Policy.requiresManualReconciliation("provider_timeout", false))
        assertTrue(VoiceIntakeV2Policy.requiresManualReconciliation("anything", true))
        assertFalse(VoiceIntakeV2Policy.requiresManualReconciliation("session_not_created", false))
        assertFalse(VoiceIntakeV2Policy.requiresManualReconciliation("chunks_missing", false))
    }

    @Test
    fun delayNeverSchedulesBeforeAuthoritativeTimestamp() {
        assertEquals(0L, VoiceIntakeV2Policy.delaySecondsUntil(999L, 1_000L))
        assertEquals(1L, VoiceIntakeV2Policy.delaySecondsUntil(1_001L, 1_000L))
        assertEquals(2L, VoiceIntakeV2Policy.delaySecondsUntil(2_001L, 1_000L))
    }

    @Test
    fun expiredRetryTimestampFallsBackToSparsePolling() {
        assertEquals(60L, VoiceIntakeV2Policy.retryDelaySeconds(0, 60L))
        assertEquals(45L, VoiceIntakeV2Policy.retryDelaySeconds(45, 60L))
    }

    @Test
    fun acceptsOnlyBoundedCaptureClockOverlapBetweenSegments() {
        assertTrue(VoiceIntakeV2Policy.wallTimelineFollows(180_758L, 180_751L))
        assertTrue(VoiceIntakeV2Policy.wallTimelineFollows(180_758L, 180_708L))
        assertFalse(VoiceIntakeV2Policy.wallTimelineFollows(180_758L, 180_707L))
    }

    private fun progress(
        retryable: Boolean,
        retryAfterSeconds: Int?,
        reconciliationRequired: Boolean = false,
    ) = RemoteProgress(
        state = RemoteState.RETRYABLE_ERROR,
        recordingFinished = true,
        chunksExpected = 1,
        chunksUploaded = 1,
        chunksTranscribed = 0,
        geminiRequestsTotal = 2,
        geminiRequestsCompleted = 0,
        transcriptionComplete = false,
        summaryComplete = false,
        githubVerified = false,
        serverAudioPurged = false,
        githubUrl = null,
        githubCommitSha = null,
        lastError = null,
        errorCode = "temporary",
        retryable = retryable,
        retryAfterSeconds = retryAfterSeconds,
        reconciliationRequired = reconciliationRequired,
    )
}
