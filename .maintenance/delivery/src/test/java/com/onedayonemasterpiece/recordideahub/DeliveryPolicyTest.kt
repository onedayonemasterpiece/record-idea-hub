package com.onedayonemasterpiece.recordideahub

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DeliveryPolicyTest {
    @Test fun everyRecordingHasAnIndependentWorkChain() {
        assertNotEquals(DeliveryPolicy.workName("old"), DeliveryPolicy.workName("new"))
        assertEquals(DeliveryPolicy.workName("old"), DeliveryPolicy.workName("old"))
    }

    @Test fun deletionRequiresAllThreeConfirmations() {
        for (state in listOf(RemoteState.PUBLISHED_VERIFIED, RemoteState.VERIFYING)) {
            for (verified in listOf(true, false)) for (purged in listOf(true, false)) {
                assertEquals(state == RemoteState.PUBLISHED_VERIFIED && verified && purged,
                    DeliveryPolicy.mayDeleteAudio(state, verified, purged))
            }
        }
    }

    @Test fun acceptedCompleteIsNotResent() {
        assertFalse(DeliveryPolicy.maySendComplete(true))
        assertTrue(DeliveryPolicy.maySendComplete(false))
    }

    @Test fun cancellationDisconnectsActiveRequestAndForbidsAnother() {
        var disconnected = 0
        val connection = object : HttpURLConnection(URL("https://example.test")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected++ }
        }
        val token = TransferCancellation()
        token.attach(connection)
        token.cancel()
        token.cancel()
        assertEquals(1, disconnected)
        try { token.attach(connection); fail("cancelled transfer was reused") }
        catch (_: CancellationException) { /* expected */ }
    }

    @Test fun completedConnectionIsNotCancelledAgain() {
        var disconnected = false
        val connection = object : HttpURLConnection(URL("https://example.test")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
        }
        val token = TransferCancellation()
        token.attach(connection)
        token.detach(connection)
        token.cancel()
        assertFalse(disconnected)
    }
}
