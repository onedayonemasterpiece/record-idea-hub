package com.onedayonemasterpiece.recordideahub

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Full SyncEngine -> HttpURLConnection -> JSON -> real SQLite. No mocked engine/API/store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncEngineReadbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: SessionStore
    private lateinit var server: HttpServer
    private lateinit var audio: List<File>
    private val requests = CopyOnWriteArrayList<String>()
    private val id = "voice-20260905-120000-abcdef12"
    private val dbName = "record-idea-hub.sqlite3"
    @Volatile private var replyCode = 200
    @Volatile private var replyBody = "{}"

    @Before fun setup() {
        context.deleteDatabase(dbName)
        store = SessionStore(context)
        store.createSession(id, "2026-09-05T12:00:00+02:00", "Europe/Kaliningrad", "fixture",
            capturePolicy = CapturePolicy.CONTINUOUS_V1, vadEngine = null)
        audio = (0..6).map { index ->
            val file = File(context.filesDir, "readback-$index.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            store.addChunk(id, index, index * 1000L, (index + 1) * 1000L,
                index * 1000L, (index + 1) * 1000L, file.absolutePath, "a".repeat(64), AudioProfile.MIME_M4A)
            store.markChunkUploaded(id, index)
            file
        }
        store.finishSession(id, "2026-09-05T12:00:07+02:00", 7000, 0)
        store.markServerInitialized(id)
        store.markCompleteSent(id)
        store.setRetryableError(id, 1, "old masked error")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
            val bytes = replyBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.set("Connection", "close")
            exchange.sendResponseHeaders(replyCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        replyBody = progress().toString()
    }

    @After fun teardown() {
        server.stop(0)
        store.close()
        audio.forEach { it.delete() }
    }

    private fun runSync(): Long? = SyncEngine(context, TransferCancellation()).run(
        id, store, "http://127.0.0.1:${server.address.port}", "non-secret-fixture", userInitiated = true,
    )

    private fun reopen() { store.close(); store = SessionStore(context) }
    private fun assertReadOnly(count: Int = 1) = assertEquals(
        List(count) { "GET /voice-intake/v2/sessions/$id" }, requests.toList(),
    )
    private fun assertAudioAndReceiptsPreserved() {
        assertTrue(audio.all { it.exists() })
        assertTrue(store.session(id)!!.completeSent)
        assertEquals(7, store.chunks(id).count { it.uploaded })
    }
    private fun assertSafeDiagnostic(type: String, phase: String, http: Int) {
        val message = store.session(id)!!.lastError.orEmpty()
        assertTrue(message, message.contains(type))
        assertTrue(message, message.contains(phase))
        assertTrue(message, message.contains("http=$http"))
        assertFalse(message, message.contains("DO_NOT_LEAK"))
        assertFalse(message, message.contains("non-secret-fixture"))
        assertFalse(message, message.contains("Передача прервана"))
    }

    @Test fun acceptedCompleteReadsStatusOnlyAndPurgesAfterAllProofs() {
        assertNull(runSync())
        reopen()
        assertEquals(RemoteState.PUBLISHED_VERIFIED, store.session(id)!!.remoteState)
        assertTrue(store.session(id)!!.githubVerified)
        assertTrue(store.session(id)!!.serverAudioPurged)
        assertTrue(audio.none { it.exists() })
        assertTrue(store.chunks(id).all { it.path.isEmpty() })
        assertReadOnly()
    }

    @Test fun processingThenTerminalSurvivesDatabaseReopenWithoutGeneration() {
        replyBody = progress("processing", verified = false, purged = false).toString()
        assertNotNull(runSync())
        reopen()
        assertAudioAndReceiptsPreserved()
        replyBody = progress().toString()
        assertNull(runSync())
        assertReadOnly(2)
        assertTrue(audio.none { it.exists() })
    }

    @Test fun malformedJsonPersistsSafeConcreteCauseAndRecoversAfterReopen() {
        replyBody = "{DO_NOT_LEAK:"
        assertNotNull(runSync())
        reopen()
        assertAudioAndReceiptsPreserved()
        assertSafeDiagnostic("JSONException", "parse_json", 200)
        replyBody = progress().toString()
        assertNull(runSync())
        assertReadOnly(2)
    }

    @Test fun httpChunksMissingAfterAcceptedCompleteNeverResetsReceipts() {
        replyCode = 409
        replyBody = """{"detail":{"code":"chunks_missing","retryable":true}}"""
        assertNotNull(runSync())
        reopen()
        assertAudioAndReceiptsPreserved()
        assertSafeDiagnostic("ApiException", "read_response", 409)
        assertReadOnly()
    }

    @Test fun localReceiptWriteFailureIsSafeAndRecoveryUsesOnlyGet() {
        store.writableDatabase.execSQL("""CREATE TRIGGER fail_receipt BEFORE UPDATE OF github_verified
            ON sessions BEGIN SELECT RAISE(ABORT, 'DO_NOT_LEAK private local failure'); END""")
        assertNotNull(runSync())
        reopen()
        assertAudioAndReceiptsPreserved()
        assertSafeDiagnostic("SQLiteConstraintException", "save_progress", 200)
        store.writableDatabase.execSQL("DROP TRIGGER fail_receipt")
        assertNull(runSync())
        assertReadOnly(2)
    }

    @Test fun serverForgettingAcceptedCompleteRequiresReadOnlyReconciliation() {
        replyBody = progress("receiving", verified = false, purged = false)
            .put("recording_finished", false).put("chunks_received", 0).toString()
        assertNotNull(runSync())
        assertAudioAndReceiptsPreserved()
        assertEquals(RemoteState.RECONCILIATION_REQUIRED, store.session(id)!!.remoteState)
        assertReadOnly()
    }

    @Test fun eachMissingProofKeepsAudio() {
        for ((state, verified, purged) in listOf(
            Triple("published_verified", false, true),
            Triple("published_verified", true, false),
            Triple("verifying", true, true),
        )) {
            replyBody = progress(state, verified, purged).toString()
            assertNotNull(runSync())
            assertAudioAndReceiptsPreserved()
        }
        assertReadOnly(3)
    }

    @Test fun reconciliation404RemainsReadOnlyAfterReopen() {
        store.setRemoteState(id, RemoteState.RECONCILIATION_REQUIRED)
        replyCode = 404
        replyBody = """{"detail":{"code":"session_not_found","retryable":false}}"""
        assertNotNull(runSync())
        reopen()
        assertAudioAndReceiptsPreserved()
        assertEquals(RemoteState.RECONCILIATION_REQUIRED, store.session(id)!!.remoteState)
        assertReadOnly()
    }

    private fun progress(state: String = "published_verified", verified: Boolean = true, purged: Boolean = true) =
        JSONObject().put("api_version", "2.0").put("session_id", id).put("state", state)
            .put("recording_finished", true).put("chunks_expected", 7).put("chunks_received", 7)
            .put("transcription_complete", verified).put("summary_complete", verified)
            .put("gemini_requests_total", 2).put("gemini_requests_completed", if (verified) 2 else 0)
            .put("github_verified", verified).put("server_audio_purged", purged)
            .put("github_url", if (verified) "https://github.com/example/fixture" else JSONObject.NULL)
            .put("github_commit_sha", if (verified) "b".repeat(40) else JSONObject.NULL)
            .put("retry_at", JSONObject.NULL).put("error_code", JSONObject.NULL)
            .put("retryable", false).put("reconciliation_required", false)
}
