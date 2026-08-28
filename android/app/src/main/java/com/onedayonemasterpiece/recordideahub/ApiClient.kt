package com.onedayonemasterpiece.recordideahub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class ApiClient(
    private val baseUrl: String,
    private val token: String,
) {
    fun createSession(session: SessionSnapshot): RemoteProgress = jsonRequest(
        method = "POST",
        path = "$API_ROOT/sessions",
        body = JSONObject()
            .put("session_id", session.sessionId)
            .put("started_at", session.startedAt)
            .put("timezone", session.timezone)
            .put("device_label", session.deviceLabel)
            .toString()
            .toByteArray(StandardCharsets.UTF_8),
    ).toProgress()

    fun uploadChunk(chunk: ChunkRecord): ChunkTranscriptResult {
        val file = File(chunk.path)
        require(file.isFile) { "missing local audio chunk ${chunk.path}" }
        val connection = open(
            "PUT",
            "$API_ROOT/sessions/${chunk.sessionId}/chunks/${chunk.chunkIndex}",
        )
        connection.setRequestProperty("Content-Type", "audio/wav")
        connection.setRequestProperty("X-Chunk-SHA256", chunk.sha256)
        connection.setRequestProperty(
            "X-Chunk-Duration-Ms",
            (chunk.endMs - chunk.startMs).coerceAtLeast(1L).toString(),
        )
        connection.setFixedLengthStreamingMode(file.length())
        connection.doOutput = true
        file.inputStream().use { input ->
            connection.outputStream.use { output -> input.copyTo(output) }
        }
        val response = readJson(connection)
        val transcript = response.getJSONObject("transcript")
        return ChunkTranscriptResult(
            transcriptJson = transcript.toString(),
            model = response.getString("model"),
            requestUid = response.getString("request_uid"),
        )
    }

    fun completeSession(
        session: SessionSnapshot,
        chunks: List<ChunkRecord>,
    ): RemoteProgress {
        require(chunks.isNotEmpty()) { "cannot complete a session without chunks" }
        require(chunks.size == session.chunkCount) { "local chunk count changed before completion" }
        val ordered = chunks.sortedBy { it.chunkIndex }
        require(ordered.map { it.chunkIndex } == ordered.indices.toList()) {
            "local chunks must be contiguous and ordered"
        }
        val chunkArray = JSONArray()
        for (chunk in ordered) {
            val transcript = chunk.transcriptJson
                ?: error("chunk ${chunk.chunkIndex} has no durable transcript")
            chunkArray.put(
                JSONObject()
                    .put("chunk_index", chunk.chunkIndex)
                    .put("start_ms", chunk.startMs)
                    .put("end_ms", chunk.endMs)
                    .put("sha256", chunk.sha256)
                    .put("transcript", JSONObject(transcript)),
            )
        }
        return jsonRequest(
            method = "POST",
            path = "$API_ROOT/sessions/${session.sessionId}/complete",
            body = JSONObject()
                .put("started_at", session.startedAt)
                .put("ended_at", requireNotNull(session.endedAt))
                .put("timezone", session.timezone)
                .put("device_label", session.deviceLabel)
                .put("duration_ms", session.durationMs)
                .put("chunk_count", session.chunkCount)
                .put("chunks", chunkArray)
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        ).toProgress()
    }

    fun status(sessionId: String): RemoteProgress = jsonRequest(
        method = "GET",
        path = "$API_ROOT/sessions/$sessionId",
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
        connection.readTimeout = 240_000
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "record-idea-hub-android/0.2")
        return connection
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw parseError(code, text)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(httpCode: Int, text: String): ApiException {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val detail = root?.optJSONObject("detail")
        val code = detail?.optString("code")
            ?.takeIf { it.isNotBlank() }
            ?: "http_$httpCode"
        val retryable = detail?.optBoolean("retryable", httpCode == 429 || httpCode >= 500)
            ?: (httpCode == 429 || httpCode >= 500)
        val retryAfter = detail?.let {
            if (it.has("retry_after_seconds") && !it.isNull("retry_after_seconds")) {
                it.optInt("retry_after_seconds").takeIf { seconds -> seconds > 0 }
            } else {
                null
            }
        }
        val reconciliationRequired = detail?.optBoolean("reconciliation_required", false) ?: false
        return ApiException(
            message = humanMessage(code, retryAfter),
            code = code,
            retryable = retryable,
            retryAfterSeconds = retryAfter,
            reconciliationRequired = reconciliationRequired,
        )
    }

    private fun humanMessage(code: String, retryAfterSeconds: Int?): String {
        val wait = retryAfterSeconds?.let { " Повтор через ${formatWait(it)}." }.orEmpty()
        return when {
            code == "provider_429" || code.startsWith("quota_exhausted_") ->
                "Доступный лимит Gemini временно исчерпан.$wait Аудио сохранено на телефоне."
            code.contains("limiter") || code.contains("reservation") ->
                "Общий контроль лимитов недоступен.$wait Аудио сохранено на телефоне."
            code.startsWith("provider_") ->
                "Gemini временно не завершил обработку.$wait Аудио сохранено на телефоне."
            code.startsWith("github_") || code.startsWith("idea_hub_") ->
                "Расшифровка сохранена локально, но GitHub ещё не подтверждён.$wait"
            code == "chunk_sha256_mismatch" ->
                "Сервер отклонил повреждённый аудиочанк; исходный файл сохранён."
            else -> "Стадия не завершена ($code).$wait Исходные данные сохранены."
        }
    }

    private fun formatWait(seconds: Int): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) {
            String.format(Locale.US, "%d мин %02d с", minutes, remainder)
        } else {
            "$remainder с"
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

    companion object {
        private const val API_ROOT = "/voice-intake/v1"
    }
}

class ApiException(
    message: String,
    val code: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int?,
    val reconciliationRequired: Boolean,
) : Exception(message)
