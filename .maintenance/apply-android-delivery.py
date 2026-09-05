from pathlib import Path
import re

root = Path('android/app/src/main/java/com/onedayonemasterpiece/recordideahub')
p = root / 'SessionStore.kt'
s = p.read_text().replace('private const val DB_VERSION = 3', 'private const val DB_VERSION = 4')
s = s.replace('                created_at INTEGER NOT NULL', '''                create_payload_json TEXT,
                github_verified INTEGER NOT NULL DEFAULT 0,
                server_audio_purged INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL''', 1)
pos = s.index('    @Synchronized\n    fun createSession(')
prefix = s[:pos]
idx = prefix.rfind('    }')
prefix = prefix[:idx] + '''        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN create_payload_json TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN github_verified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN server_audio_purged INTEGER NOT NULL DEFAULT 0")
            // Old rows did not persist these confirmations. Re-read the server before
            // deleting remaining files; already deleted audio is not recreated.
            db.execSQL("UPDATE sessions SET remote_state='verifying' WHERE remote_state='published_verified' AND audio_deleted=0")
        }
''' + prefix[idx:]
s = prefix + s[pos:]
s = s.replace('AND remote_state NOT IN (?, ?)', 'AND remote_state != ?')
s = s.replace('                RemoteState.RECONCILIATION_REQUIRED,\n                nowEpochMs.toString(),', '                nowEpochMs.toString(),')
s = s.replace('                put("remote_state", progress.state)', '''                val safeState = if (progress.state == RemoteState.PUBLISHED_VERIFIED &&
                    !DeliveryPolicy.mayDeleteAudio(progress.state, progress.githubVerified, progress.serverAudioPurged)
                ) RemoteState.VERIFYING else progress.state
                put("remote_state", safeState)
                put("github_verified", if (progress.githubVerified) 1 else 0)
                put("server_audio_purged", if (progress.serverAudioPurged) 1 else 0)''', 1)
s = s.replace('"remote_state=? AND audio_deleted=0",', '"remote_state=? AND github_verified=1 AND server_audio_purged=1 AND audio_deleted=0",')
s = s.replace('    fun deleteVerifiedAudio(sessionId: String): Boolean {\n', '''    fun deleteVerifiedAudio(sessionId: String): Boolean {
        val current = session(sessionId) ?: return false
        if (!DeliveryPolicy.mayDeleteAudio(current.remoteState, current.githubVerified, current.serverAudioPurged)) return false
''', 1)
s = s.replace('        retryAtEpochMs = nullableLong("retry_at_epoch_ms"),', '''        retryAtEpochMs = nullableLong("retry_at_epoch_ms"),
        githubVerified = getInt(getColumnIndexOrThrow("github_verified")) != 0,
        serverAudioPurged = getInt(getColumnIndexOrThrow("server_audio_purged")) != 0,''', 1)
s = s.replace('"chunks_transcribed", "github_url", "github_commit_sha", "last_error", "retry_at_epoch_ms",', '"chunks_transcribed", "github_url", "github_commit_sha", "last_error", "retry_at_epoch_ms",\n            "github_verified", "server_audio_purged",')
needle = '    @Synchronized\n    fun markServerInitialized'
s = s.replace(needle, '''    @Synchronized
    fun createPayload(sessionId: String, factory: () -> String): String {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT create_payload_json FROM sessions WHERE session_id=?", arrayOf(sessionId))
                .use { cursor ->
                    check(cursor.moveToFirst()) { "session disappeared before create" }
                    if (cursor.isNull(0)) null else cursor.getString(0)
                }
            val payload = existing ?: factory().also {
                db.update("sessions", ContentValues().apply { put("create_payload_json", it) },
                    "session_id=?", arrayOf(sessionId))
            }
            db.setTransactionSuccessful()
            return payload
        } finally { db.endTransaction() }
    }

''' + needle)
p.write_text(s)
p = root / 'Models.kt'
s = p.read_text().replace('    val retryAtEpochMs: Long?,\n', '    val retryAtEpochMs: Long?,\n    val githubVerified: Boolean = false,\n    val serverAudioPurged: Boolean = false,\n', 1)
p.write_text(s)
p = root / 'ApiClient.kt'
s = p.read_text()
s = s.replace('class ApiClientV1(baseUrl: String, token: String) {', 'class ApiClientV1(baseUrl: String, token: String, cancellation: TransferCancellation = TransferCancellation()) {')
s = s.replace('class ApiClientV2(baseUrl: String, token: String) {', 'class ApiClientV2(baseUrl: String, token: String, cancellation: TransferCancellation = TransferCancellation()) {')
s = s.replace('private val http = VoiceHttpClient(baseUrl, token)', 'private val http = VoiceHttpClient(baseUrl, token, cancellation)')
s = s.replace('    fun createSession(session: SessionSnapshot): RemoteProgress {\n        val body = JSONObject()', '''    fun createSession(session: SessionSnapshot, store: SessionStore): RemoteProgress =
        http.jsonRequest("POST", "$API_ROOT/sessions", store.createPayload(session.sessionId) {
            creationPayload(session).toString()
        }.toByteArray(StandardCharsets.UTF_8)).requireV2Session(session.sessionId).toProgressV2()

    internal fun creationPayload(session: SessionSnapshot): JSONObject {
        val body = JSONObject()''', 1)
s = s.replace('''        return http.jsonRequest("POST", "$API_ROOT/sessions", body.toBytes())
            .requireV2Session(session.sessionId)
            .toProgressV2()''', '        return body', 1)
s = s.replace('private class VoiceHttpClient(baseUrl: String, private val token: String) {', '''private class VoiceHttpClient(
    baseUrl: String, private val token: String, private val cancellation: TransferCancellation,
) {''')
start = s.index('    fun jsonRequest(method: String')
end = s.index('    private fun open(', start)
s = s[:start] + '''    fun jsonRequest(method: String, path: String, body: ByteArray?): JSONObject =
        request(method, path) { connection ->
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
        }

    fun upload(path: String, file: File, mimeType: String, headers: Map<String, String>): JSONObject {
        require(file.isFile) { "missing local audio chunk" }
        return request("PUT", path) { connection ->
            connection.setRequestProperty("Content-Type", mimeType)
            headers.forEach(connection::setRequestProperty)
            connection.setFixedLengthStreamingMode(file.length())
            connection.doOutput = true
            file.inputStream().use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        cancellation.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun request(method: String, path: String, body: (HttpURLConnection) -> Unit): JSONObject {
        cancellation.check()
        val connection = open(method, path)
        try {
            cancellation.attach(connection)
            body(connection)
            cancellation.check()
            return readJson(connection)
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

''' + s[end:]
p.write_text(s)
old = (root / 'SyncWorker.kt').read_text()
methods = old[old.index('    private fun syncV2'):]
methods = methods.replace('applicationContext', 'context')
methods = re.sub(r'SyncScheduler.enqueue\(context, ([A-Z_]+|delay)\)', r'schedule(\1)', methods)
methods = methods.replace('api.createSession(original)', 'api.createSession(original, store)', 1)
methods = methods.replace('''            val progress = if (VoiceIntakeV2Policy.shouldRetryComplete(initialized)) {
                api.completeSession(session, store.chunks(session.sessionId))
            } else {
                initialized
            }''', '''            // Once complete is accepted the server owns processing and retries.
            val progress = initialized''')
methods = methods.replace('''            if (VoiceIntakeV2Policy.shouldRetryComplete(current)) {
                api.completeSession(session, chunks)
            } else {
                current
            }''', '''            if (DeliveryPolicy.maySendComplete(current.recordingFinished)) api.completeSession(session, chunks)
            else current''')
methods = methods.replace('        githubVerified && serverAudioPurged', '        DeliveryPolicy.mayDeleteAudio(state, githubVerified, serverAudioPurged)')
methods = methods.replace('        val initialized = api.createSession(original, store)', '''        val initialized = if (original.remoteState == RemoteState.RECONCILIATION_REQUIRED) {
            try { api.status(original.sessionId) } catch (exc: ApiException) {
                if (exc.code == "session_not_found" || exc.code == "http_404") {
                    schedule(300L)
                    return
                }
                throw exc
            }
        } else api.createSession(original, store)
        if (original.remoteState == RemoteState.RECONCILIATION_REQUIRED) {
            if (initialized.isVerifiedAndPurged()) {
                store.updateRemoteProgress(original.sessionId, initialized)
                deleteLocalAudioOrRetry(store, original.sessionId)
            } else {
                // Observe repairs without reopening uploads or replaying complete.
                schedule(300L)
            }
            return
        }''', 1)
methods = methods.replace('''            return
        }
        store.markServerInitialized''', '''            schedule(300L)
            return
        }
        store.markServerInitialized''', 1)
methods = methods.replace('''            return
        }

        var session = store.session(original.sessionId)''', '''            schedule(300L)
            return
        }

        var session = store.session(original.sessionId)''', 1)
methods = methods.replace('''                    progress.errorCode ?: "Сервер требует безопасную сверку; аудио сохранено",
                )''', '''                    progress.errorCode ?: "Сервер требует безопасную сверку; аудио сохранено",
                )
                schedule(300L)''')
methods = methods.replace('''                    progress.errorCode ?: "Требуется исправить или сверить данные сессии",
                )''', '''                    progress.errorCode ?: "Требуется исправить или сверить данные сессии",
                )
                schedule(300L)''')
methods = methods.replace('''            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
            return''', '''            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
            schedule(300L)
            return''')
methods = methods.replace('if (initialized.recordingFinished) {', 'if (!DeliveryPolicy.maySendComplete(initialized.recordingFinished)) {')
engine = '''package com.onedayonemasterpiece.recordideahub

import android.content.Context
import java.util.concurrent.CancellationException

internal class SyncEngine(
    private val context: Context,
    private val cancellation: TransferCancellation,
    private val onProgress: () -> Unit = {},
) {
    private var nextDelay: Long? = null
    private fun schedule(seconds: Long) {
        nextDelay = minOf(nextDelay ?: Long.MAX_VALUE, seconds)
    }

    fun run(sessionId: String, userInitiated: Boolean = false): Long? {
        val config = AppGraph.config(context)
        val url = config.backendUrl ?: return null
        val token = config.deviceToken ?: return null
        val store = AppGraph.store(context)
        val session = store.session(sessionId) ?: return null
        if (session.captureState != CaptureState.FINISHED && session.captureState != CaptureState.PAUSED) return null
        val due = session.retryAtEpochMs?.let { VoiceIntakeV2Policy.delaySecondsUntil(it) } ?: 0L
        if (due > 0L && (!userInitiated || session.remoteState == RemoteState.WAITING_FOR_QUOTA)) return due
        if (DeliveryPolicy.mayDeleteAudio(session.remoteState, session.githubVerified, session.serverAudioPurged)) {
            return if (store.deleteVerifiedAudio(sessionId)) null else 60L
        }
        try {
            cancellation.check()
            if (session.protocolVersion >= 2) syncV2(store, ApiClientV2(url, token, cancellation), session)
            else syncLegacyV1(store, ApiClientV1(url, token, cancellation), session)
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: ApiException) {
            cancellation.check()
            if (session.remoteState == RemoteState.RECONCILIATION_REQUIRED) schedule(300L)
            else handleApiFailure(store, sessionId, exc)
        } catch (exc: IllegalArgumentException) {
            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
            schedule(300L)
        } catch (exc: IllegalStateException) {
            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
            schedule(300L)
        } catch (exc: Exception) {
            cancellation.check()
            if (session.remoteState != RemoteState.RECONCILIATION_REQUIRED) {
                store.setRetryableError(sessionId, 30L, "Передача прервана; аудио сохранено, повтор запланирован")
            }
            schedule(30L)
        }
        onProgress()
        return nextDelay
    }

''' + methods
engine = engine.replace('''            api.uploadChunk(chunk)
            store.markChunkUploaded''', '''            cancellation.check()
            api.uploadChunk(chunk)
            store.markChunkUploaded''')
engine = engine.replace('            store.markChunkUploaded(session.sessionId, chunk.chunkIndex)\n', '            store.markChunkUploaded(session.sessionId, chunk.chunkIndex)\n            onProgress()\n')
(root / 'SyncEngine.kt').write_text(engine)
p = root / 'RecordingService.kt'
s = p.read_text()
needle = '''        runtime.clear(active.sessionId)
        SyncScheduler.enqueue(this)
        stopForeground'''
assert needle in s
s = s.replace(needle, '''        runtime.clear(active.sessionId)
        SyncScheduler.userTransfer(this, active.sessionId)
        stopForeground''', 1)
p.write_text(s)
p = root / 'MainActivity.kt'
s = p.read_text().replace('''        store.setRemoteState(session.sessionId, RemoteState.RECEIVING, "Повтор запрошен; аудио сохранено")
        SyncScheduler.enqueue(this)''', '''        // Do not erase a conflict, quota deadline, or inference ambiguity.
        SyncScheduler.userTransfer(this, session.sessionId)''')
s = s.replace('        retry.visibility = if (latest.remoteState in setOf(', '''        retry.text = if (latest.remoteState == RemoteState.RECONCILIATION_REQUIRED) "Проверить результат" else "Повторить передачу"
        retry.visibility = if (latest.remoteState in setOf(''', 1)
p.write_text(s)
p = root / 'RecordIdeaHubApp.kt'
p.write_text(p.read_text().replace('        SyncScheduler.enqueue(this)', '        SyncScheduler.initialize(this)'))
p = Path('android/app/src/main/AndroidManifest.xml')
s = p.read_text().replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">')
s = s.replace('    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />', '''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.RUN_USER_INITIATED_JOBS" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />''')
s = s.replace('    </application>', '''        <service android:name=".TransferJobService"
            android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false" />
        <service android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync" tools:node="merge" />
    </application>''')
p.write_text(s)
p = root / 'VoiceIntakeV2Policy.kt'
s = p.read_text().replace('        progress.retryable &&', '        !progress.recordingFinished && progress.retryable &&')
p.write_text(s)
p = Path('android/app/build.gradle.kts')
s = p.read_text().replace('versionCode = 4', 'versionCode = 5').replace('versionName = "1.1.0-rc3"', 'versionName = "1.1.0-rc4"')
s = s.replace('    buildFeatures {', '    testOptions { unitTests.isIncludeAndroidResources = true }\n\n    buildFeatures {')
s = s.replace('    testImplementation("junit:junit:4.13.2")', '    testImplementation("junit:junit:4.13.2")\n    testImplementation("org.robolectric:robolectric:4.16.1")')
p.write_text(s)
p = Path('android/app/src/test/java/com/onedayonemasterpiece/recordideahub/VoiceIntakeV2PolicyTest.kt')
s = p.read_text().replace('assertTrue(VoiceIntakeV2Policy.shouldRetryComplete(ready))', 'assertFalse(VoiceIntakeV2Policy.shouldRetryComplete(ready))\n        assertTrue(VoiceIntakeV2Policy.shouldRetryComplete(ready.copy(recordingFinished = false)))')
p.write_text(s)
# The replacement scheduler and new support classes are supplied as source fixtures.
for source in Path('.maintenance/delivery').rglob('*'):
    if source.is_file():
        target = Path('android/app') / source.relative_to('.maintenance/delivery')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
