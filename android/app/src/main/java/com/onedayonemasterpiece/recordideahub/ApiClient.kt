package com.onedayonemasterpiece.recordideahub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class ApiClientV1(baseUrl: String, token: String) {
    private val http = VoiceHttpClient(baseUrl, token)

    fun createSession(session: SessionSnapshot): RemoteProgress = http.jsonRequest(
        "POST", "$API_ROOT/sessions", JSONObject()
            .put("session_id", session.sessionId)
            .put("started_at", session.startedAt)
            .put("timezone", session.timezone)
            .put("device_label", session.deviceLabel).toBytes(),
    ).toProgressV1()

    fun uploadChunk(chunk: ChunkRecord): ChunkTranscriptResult {
        val response = http.upload(
            "$API_ROOT/sessions/${chunk.sessionId}/chunks/${chunk.chunkIndex}", File(chunk.path), "audio/wav",
            mapOf(
                "X-Chunk-SHA256" to chunk.sha256,
                "X-Chunk-Duration-Ms" to chunk.durationMs.coerceAtLeast(1L).toString(),
            ),
        )
        return ChunkTranscriptResult(
            transcriptJson = response.getJSONObject("transcript").toString(),
            model = response.getString("model"), requestUid = response.getString("request_uid"),
        )
    }

    fun completeSession(session: SessionSnapshot, chunks: List<ChunkRecord>): RemoteProgress {
        val ordered = requireOrderedChunks(session, chunks)
        val array = JSONArray()
        ordered.forEach { chunk ->
            array.put(JSONObject()
                .put("chunk_index", chunk.chunkIndex).put("start_ms", chunk.startMs)
                .put("end_ms", chunk.endMs).put("sha256", chunk.sha256)
                .put("transcript", JSONObject(chunk.transcriptJson ?: error("missing legacy transcript"))))
        }
        return http.jsonRequest(
            "POST", "$API_ROOT/sessions/${session.sessionId}/complete", JSONObject()
                .put("started_at", session.startedAt).put("ended_at", requireNotNull(session.endedAt))
                .put("timezone", session.timezone).put("device_label", session.deviceLabel)
                .put("duration_ms", session.durationMs).put("chunk_count", session.chunkCount)
                .put("chunks", array).toBytes(),
        ).toProgressV1()
    }

    fun status(sessionId: String): RemoteProgress =
        http.jsonRequest("GET", "$API_ROOT/sessions/$sessionId", null).toProgressV1()

    companion object { private const val API_ROOT = "/voice-intake/v1" }
}

class ApiClientV2(baseUrl: String, token: String) {
    private val http = VoiceHttpClient(baseUrl, token)

    fun createSession(session: SessionSnapshot): RemoteProgress {
        val body = JSONObject()
            .put("session_id", session.sessionId).put("started_at", session.startedAt)
            .put("timezone", session.timezone).put("device_label", session.deviceLabel)
            .put("client_version", BuildConfig.VERSION_NAME).put("capture_policy", session.capturePolicy)
            .put("audio_format", JSONObject()
                .put("container", AudioProfile.CONTAINER).put("codec", AudioProfile.CODEC)
                .put("mime_type", AudioProfile.MIME_M4A).put("sample_rate_hz", AudioProfile.SAMPLE_RATE_HZ)
                .put("channels", AudioProfile.CHANNELS).put("target_bitrate_bps", AudioProfile.BITRATE_BPS))
        if (session.vadEngine.isNullOrBlank()) {
            body.put("vad", JSONObject.NULL)
        } else {
            body.put("vad", JSONObject()
                .put("engine", session.vadEngine).put("engine_version", "2.0.10-cf.4")
                .put("mode", 1).put("frame_ms", EfficientVad.FRAME_MS)
                .put("config_version", "vad-auto-pause-efficient-v1"))
        }
        return http.jsonRequest("POST", "$API_ROOT/sessions", body.toBytes()).toProgressV2()
    }

    fun uploadChunk(chunk: ChunkRecord): ChunkUploadReceipt {
        val response = http.upload(
            "$API_ROOT/sessions/${chunk.sessionId}/chunks/${chunk.chunkIndex}", File(chunk.path), chunk.mimeType,
            mapOf(
                "X-Chunk-SHA256" to chunk.sha256,
                "X-Chunk-Duration-Ms" to chunk.durationMs.coerceAtLeast(1L).toString(),
                "X-Audio-Start-Ms" to chunk.startMs.toString(),
                "X-Audio-End-Ms" to chunk.endMs.toString(),
                "X-Wall-Start-Ms" to chunk.wallStartMs.toString(),
                "X-Wall-End-Ms" to chunk.wallEndMs.toString(),
            ),
        )
        return ChunkUploadReceipt(
            response.optInt("chunk_index", chunk.chunkIndex), response.optBoolean("accepted", true),
            response.optBoolean("duplicate", false), response.optInt("chunks_received", 0),
            response.optLong("bytes_received", 0L),
        )
    }

    fun completeSession(session: SessionSnapshot, chunks: List<ChunkRecord>): RemoteProgress {
        val ordered = requireOrderedChunks(session, chunks)
        val manifest = JSONArray()
        ordered.forEach { chunk -> manifest.put(JSONObject()
            .put("chunk_index", chunk.chunkIndex).put("sha256", chunk.sha256)
            .put("duration_ms", chunk.durationMs).put("audio_start_ms", chunk.startMs)
            .put("audio_end_ms", chunk.endMs).put("wall_start_ms", chunk.wallStartMs)
            .put("wall_end_ms", chunk.wallEndMs)) }
        return http.jsonRequest(
            "POST", "$API_ROOT/sessions/${session.sessionId}/complete", JSONObject()
                .put("ended_at", requireNotNull(session.endedAt)).put("wall_elapsed_ms", session.wallElapsedMs)
                .put("manual_pause_ms", session.manualPauseMs).put("recorded_audio_ms", session.durationMs)
                .put("auto_silence_skipped_ms", session.autoSilenceSkippedMs)
                .put("chunk_count", session.chunkCount).put("chunks", manifest).toBytes(),
        ).toProgressV2()
    }

    fun status(sessionId: String): RemoteProgress =
        http.jsonRequest("GET", "$API_ROOT/sessions/$sessionId", null).toProgressV2()

    companion object { private const val API_ROOT = "/voice-intake/v2" }
}

private class VoiceHttpClient(private val baseUrl: String, private val token: String) {
    fun jsonRequest(method: String, path: String, body: ByteArray?): JSONObject {
        val connection = open(method, path)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return readJson(connection)
    }

    fun upload(path: String, file: File, mimeType: String, headers: Map<String, String>): JSONObject {
        require(file.isFile) { "missing local audio chunk ${file.absolutePath}" }
        val connection = open("PUT", path)
        connection.setRequestProperty("Content-Type", mimeType)
        headers.forEach(connection::setRequestProperty)
        connection.setFixedLengthStreamingMode(file.length())
        connection.doOutput = true
        file.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }
        return readJson(connection)
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 180_000
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "record-idea-hub-android/${BuildConfig.VERSION_NAME}")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw parseError(code, text)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun parseError(httpCode: Int, text: String): ApiException {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val detail = root?.optJSONObject("detail")
        val code = detail?.optString("code")?.takeIf { it.isNotBlank() }
            ?: root?.optString("detail")?.takeIf { it.isNotBlank() } ?: "http_$httpCode"
        val retryable = detail?.optBoolean("retryable", httpCode == 429 || httpCode >= 500)
            ?: (httpCode == 429 || httpCode >= 500)
        val retryAfter = detail?.let {
            if (it.has("retry_after_seconds") && !it.isNull("retry_after_seconds"))
                it.optInt("retry_after_seconds").takeIf { value -> value > 0 } else null
        }
        val reconcile = detail?.optBoolean("reconciliation_required", false) ?: false
        return ApiException(humanMessage(code, retryAfter, reconcile), code, retryable, retryAfter, reconcile)
    }

    private fun humanMessage(code: String, retry: Int?, reconcile: Boolean): String {
        val wait = retry?.let { " Повтор через ${formatWait(it)}." }.orEmpty()
        return when {
            reconcile -> "Серверу требуется сверка отправленного запроса; аудио сохранено на телефоне."
            code == "provider_429" || code.startsWith("quota_exhausted_") ->
                "Доступный лимит Gemini временно исчерпан.$wait Аудио сохранено на телефоне."
            code.contains("limiter") || code.contains("reservation") ->
                "Общий контроль лимитов недоступен.$wait Аудио сохранено на телефоне."
            code.startsWith("provider_") -> "Gemini временно не завершил обработку.$wait Аудио сохранено."
            code.startsWith("github_") || code.startsWith("idea_hub_") ->
                "Расшифровка готова, но GitHub ещё не подтверждён.$wait"
            code.contains("sha256") -> "Сервер отклонил повреждённый аудиосегмент; исходник сохранён."
            code.contains("session_not_initialized") || code.contains("terminology_not_initialized") ->
                "Серверная сессия не инициализирована; приложение повторит создание и отправку."
            else -> "Стадия не завершена ($code).$wait Исходные данные сохранены."
        }
    }

    private fun formatWait(seconds: Int): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) String.format(Locale.US, "%d мин %02d с", minutes, remainder) else "$remainder с"
    }
}

private fun requireOrderedChunks(session: SessionSnapshot, chunks: List<ChunkRecord>): List<ChunkRecord> {
    require(chunks.isNotEmpty()) { "cannot complete a session without chunks" }
    require(chunks.size == session.chunkCount) { "local chunk count changed before completion" }
    return chunks.sortedBy { it.chunkIndex }.also { ordered ->
        require(ordered.map { it.chunkIndex } == ordered.indices.toList()) { "chunks must be contiguous" }
    }
}

private fun JSONObject.toBytes() = toString().toByteArray(StandardCharsets.UTF_8)
private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }
private fun JSONObject.nullableInt(name: String): Int? = if (!has(name) || isNull(name)) null else optInt(name)

private fun JSONObject.toProgressV1() = RemoteProgress(
    optString("state", RemoteState.PROCESSING), optBoolean("recording_finished", false),
    nullableInt("chunks_expected"), optInt("chunks_uploaded", 0), optInt("chunks_transcribed", 0),
    0, 0, optBoolean("github_verified", false), optBoolean("github_verified", false),
    optBoolean("github_verified", false), optBoolean("github_verified", false),
    nullableString("github_url"), nullableString("github_commit_sha"), nullableString("last_error"),
    null, false, nullableInt("retry_after_seconds"), false,
)

private fun JSONObject.toProgressV2(): RemoteProgress {
    val transcriptionComplete = optBoolean("transcription_complete", false)
    val expected = optInt("chunks_expected", optInt("chunks_received", 0))
    return RemoteProgress(
        optString("state", RemoteState.RECEIVING), optBoolean("recording_finished", false),
        nullableInt("chunks_expected"), optInt("chunks_received", optInt("chunks_uploaded", 0)),
        if (transcriptionComplete) expected else 0,
        optInt("gemini_requests_total", 2), optInt("gemini_requests_completed", 0),
        transcriptionComplete, optBoolean("summary_complete", false), optBoolean("github_verified", false),
        optBoolean("server_audio_purged", false), nullableString("github_url"),
        nullableString("github_commit_sha"), nullableString("last_error"), nullableString("error_code"),
        optBoolean("retryable", false), nullableInt("retry_after_seconds"),
        optBoolean("reconciliation_required", false),
    )
}

class ApiException(
    message: String,
    val code: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int?,
    val reconciliationRequired: Boolean,
) : Exception(message)
