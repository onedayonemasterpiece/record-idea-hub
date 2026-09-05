package com.onedayonemasterpiece.recordideahub

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SessionStoreReliabilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: SessionStore
    private val id = "voice-20260905-120000-abcdef12"
    private val dbName = "record-idea-hub.sqlite3"

    @Before fun setup() {
        context.deleteDatabase(dbName)
        store = SessionStore(context)
    }

    @After fun close() { store.close() }

    private fun session(): File {
        store.createSession(id, "2026-09-05T12:00:00+02:00", "Europe/Kaliningrad", "test",
            capturePolicy = CapturePolicy.CONTINUOUS_V1, vadEngine = null)
        val audio = File(context.filesDir, "test-audio.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.addChunk(id, 0, 0, 1000, 0, 1000, audio.absolutePath, "a".repeat(64), AudioProfile.MIME_M4A)
        store.finishSession(id, "2026-09-05T12:00:01+02:00", 1000, 0)
        return audio
    }

    @Test fun createEnvelopeSurvivesReopenAndIgnoresNewAppVersionFactory() {
        session()
        assertEquals("original-rc3-payload", store.createPayload(id) { "original-rc3-payload" })
        store.close()
        store = SessionStore(context)
        assertEquals("original-rc3-payload", store.createPayload(id) { error("must not regenerate") })
        assertEquals(1, store.chunks(id).size)
    }

    @Test fun reconciliationRemainsScheduledForReadOnlyServerRepair() {
        session()
        store.setRemoteState(id, RemoteState.RECONCILIATION_REQUIRED, "provider_outcome_ambiguous")
        assertEquals(listOf(id), store.sessionsNeedingSync(Long.MAX_VALUE).map { it.sessionId })
    }

    @Test fun stateLabelWithoutProofCannotDeleteAudio() {
        val audio = session()
        store.setRemoteState(id, RemoteState.PUBLISHED_VERIFIED)
        assertFalse(store.deleteVerifiedAudio(id))
        store.deleteAllVerifiedAudio()
        assertTrue(audio.exists())
        store.updateRemoteProgress(id, progress(verified = true, purged = false))
        assertEquals(RemoteState.VERIFYING, store.session(id)?.remoteState)
        assertFalse(store.deleteVerifiedAudio(id))
        assertTrue(audio.exists())
        store.updateRemoteProgress(id, progress(verified = true, purged = true))
        assertTrue(store.deleteVerifiedAudio(id))
        assertFalse(audio.exists())
    }

    @Test fun versionThreeMigrationKeepsQueueAndRequiresFreshDeletionProof() {
        store.close()
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val sql = javaClass.classLoader!!.getResourceAsStream("session-store-v3.sql")!!
            .bufferedReader().use { it.readText() }
        sql.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) }
        db.execSQL("""INSERT INTO sessions(session_id,started_at,timezone,device_label,capture_state,remote_state,created_at)
            VALUES(?, '2026-09-05T12:00:00+02:00', 'Europe/Kaliningrad', 'old', 'finished', 'published_verified', 1)""",
            arrayOf(id))
        db.close()
        store = SessionStore(context)
        val migrated = store.session(id)!!
        assertEquals(RemoteState.VERIFYING, migrated.remoteState)
        assertFalse(migrated.githubVerified)
        assertFalse(migrated.serverAudioPurged)
        assertEquals(id, store.sessionsNeedingSync().single().sessionId)
        assertEquals("first-stable-envelope", store.createPayload(id) { "first-stable-envelope" })
        assertEquals(4, store.readableDatabase.version)
    }

    private fun progress(verified: Boolean, purged: Boolean) = RemoteProgress(
        state = RemoteState.PUBLISHED_VERIFIED, recordingFinished = true, chunksExpected = 1,
        chunksUploaded = 1, chunksTranscribed = 1, geminiRequestsTotal = 2, geminiRequestsCompleted = 2,
        transcriptionComplete = true, summaryComplete = true, githubVerified = verified,
        serverAudioPurged = purged, githubUrl = "https://github.com/example", githubCommitSha = "b".repeat(40),
        lastError = null, errorCode = null, retryable = false, retryAfterSeconds = null,
        reconciliationRequired = false,
    )
}
