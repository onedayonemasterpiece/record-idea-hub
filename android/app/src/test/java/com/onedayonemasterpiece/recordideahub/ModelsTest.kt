package com.onedayonemasterpiece.recordideahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ModelsTest {
    @Test
    fun sessionIdIsRegistrySafe() {
        val id = newSessionId(OffsetDateTime.parse("2026-08-27T12:34:56-04:00"))
        assertTrue(id.matches(Regex("^[a-z0-9][a-z0-9._-]+$")))
        assertTrue(id.startsWith("voice-20260827-123456-"))
    }

    @Test
    fun durationFormattingIsCompact() {
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("01:01:01", formatDuration(3_661_000))
    }
}
