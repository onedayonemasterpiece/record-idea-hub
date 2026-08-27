package com.onedayonemasterpiece.recordideahub

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApiClient(
    private val baseUrl: String,
    private val token: String,
) {
    fun createSession(session: SessionSnapshot): RemoteProgress = jsonRequest(
        method = "POST",
        path = "/v1/sessions",
        body = JSONObject()
            .put("session_id", session.sessionId)
            .put("started_at", session.startedAt)
            .put("timezone", session.timezone)
            .put("device_label", session.deviceLabel)
            .toString()
            .toByteArray(),
    ).toProgress()

    fun uploadChunk(chunk: ChunkRecord) {
        val file = File(chunk.path)
        require(file.isFile) { "missing local audio chunk ${chunk.path}" }
        val connection = open("PUT", "/v1/sessions/${chunk.sessionId}/chunks/${chunk.chunkIndex}")
        connection.setRequestProperty("Content-Type", "audio/wav")
        connection.setRequestProperty("X-Chunk-SHA256", chunk.sha256)
        connection.setRequestProperty("X-Chunk-Start-Ms", chunk.startMs.toString())
        connection.setRequestProperty("X-Chunk-End-Ms", chunk.endMs.toString())
        connection.setFixedLengthStreamingMode(file.length())
        connection.doOutput = true
        file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
        readJson(connection)
    }

    fun completeSession(session: SessionSnapshot): RemoteProgress = jsonRequest(
        method = "POST",
        path = "/v1/sessions/${session.sessionId}/complete",
        body = JSONObject()
            .put("ended_at", requireNotNull(session.endedAt))
            .put("duration_ms", session.durationMs)
            .put("chunk_count", session.chunkCount)
            .toString()
            .toByteArray(),
    ).toProgress()

    fun status(sessionId: String): RemoteProgress = jsonRequest(
        method = "GET",
        path = "/v1/sessions/$sessionId",
        body = null,
    ).toProgress()

    private fun jsonRequest(method: String, path: String, body: ByteArray?): JSONObject {
        val connection = open(method, path)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return readJson(connection)
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "record-idea-hub-android/0.1")
        return connection
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw ApiException(message, retryable = code == 429 || code >= 500)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toProgress() = RemoteProgress(
        state = getString("state"),
        recordingFinished = optBoolean("recording_finished", false),
        chunksExpected = if (isNull("chunks_expected")) null else optInt("chunks_expected"),
        chunksUploaded = optInt("chunks_uploaded", 0),
        chunksTranscribed = optInt("chunks_transcribed", 0),
        githubVerified = optBoolean("github_verified", false),
        githubUrl = optString("github_url").takeIf { it.isNotBlank() && it != "null" },
        githubCommitSha = optString("github_commit_sha").takeIf { it.isNotBlank() && it != "null" },
        lastError = optString("last_error").takeIf { it.isNotBlank() && it != "null" },
        retryAfterSeconds = if (isNull("retry_after_seconds")) null else optInt("retry_after_seconds"),
    )
}

class ApiException(message: String, val retryable: Boolean) : Exception(message)
