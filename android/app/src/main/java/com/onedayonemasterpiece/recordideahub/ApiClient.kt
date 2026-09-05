package com.onedayonemasterpiece.recordideahub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.ceil

class ApiClientV1(baseUrl: String, token: String, cancellation: TransferCancellation = TransferCancellation()) {
    private val http = VoiceHttpClient(baseUrl, token, cancellation)

    fun createSession(session: SessionSnapshot): RemoteProgress = http.jsonRequest(
        "POST", "$API_ROOT/sessions", JSONObject()
            .put("session_id", session.sessionId)
            .put("started_at", session.startedAt)
            .put("timezone", session.timezone)
            .put("device_label", session.deviceLabel).toBytes(),
    ).toProgressV1()

    fun uploadChunk(chunk: ChunkRecord): ChunkTranscriptResult {
        val response = http.upload(
            "$API_ROOT/sessions/${chunk.sessionId}/chunks/${chunk.chunkIndex}",
            File(chunk.path),
            "audio/wav",
            mapOf(
                "X-Chunk-SHA256" to chunk.sha256,
                "X-Chunk-Duration-Ms" to chunk.durationMs.coerceAtLeast(1L).toString(),
            ),
        )
        return ChunkTranscriptResult(
            transcriptJson = response.getJSONObject("transcript").toString(),
            model = response.getString("model"),
            requestUid = response.getString("request_uid"),
        )
    }

    fun completeSession(session: SessionSnapshot, chunks: List<ChunkRecord>): RemoteProgress {
        val ordered = requireOrderedChunks(session, chunks)
        val array = JSONArray()
        ordered.forEach { chunk ->
            array.put(
                JSONObject()
                    .put("chunk_index", chunk.chunkIndex)
                    .put("start_ms", chunk.startMs)
                    .put("end_ms", chunk.endMs)
                    .put("sha256", chunk.sha256)
                    .put("transcript", JSONObject(chunk.transcriptJson ?: error("missing legacy transcript"))),
            )
        }
        return http.jsonRequest(
            "POST", "$API_ROOT/sessions/${session.sessionId}/complete", JSONObject()
                .put("started_at", session.startedAt)
                .put("ended_at", requireNotNull(session.endedAt))
                .put("timezone", session.timezone)
                .put("device_label", session.deviceLabel)
                .put("duration_ms", session.durationMs)
                .put("chunk_count", session.chunkCount)
                .put("chunks", array)
                .toBytes(),
        ).toProgressV1()
    }

    fun status(sessionId: String): RemoteProgress =
        http.jsonRequest("GET", "$API_ROOT/sessions/$sessionId", null).toProgressV1()

    companion object {
        private const val API_ROOT = "/voice-intake/v1"
    }
}

class ApiClientV2(baseUrl: String, token: String, cancellation: TransferCancellation = TransferCancellation()) {
    private val http = VoiceHttpClient(baseUrl, token, cancellation)

    fun createSession(session: SessionSnapshot, store: SessionStore): RemoteProgress =
        http.jsonRequest("POST", "$API_ROOT/sessions", store.createPayload(session.sessionId) {
            creationPayload(session).toString()
        }.toByteArray(StandardCharsets.UTF_8)).requireV2Session(session.sessionId).toProgressV2()

    internal fun creationPayload(session: SessionSnapshot): JSONObject {
        val body = JSONObject()
            .put("session_id", session.sessionId)
            .put("started_at", session.startedAt)
            .put("timezone", session.timezone)
            .put("device_label", session.deviceLabel)
            .put("client_version", BuildConfig.VERSION_NAME)
            .put("capture_policy", session.capturePolicy)
            .put(
                "audio_format",
                JSONObject()
                    .put("container", AudioProfile.CONTAINER)
                    .put("codec", AudioProfile.CODEC)
                    .put("mime_type", AudioProfile.MIME_M4A)
                    .put("sample_rate_hz", AudioProfile.SAMPLE_RATE_HZ)
                    .put("channels", AudioProfile.CHANNELS)
                    .put("target_bitrate_bps", AudioProfile.BITRATE_BPS),
            )
        if (session.vadEngine.isNullOrBlank()) {
            body.put("vad", JSONObject.NULL)
        } else {
            body.put(
                "vad",
                JSONObject()
                    .put("engine", session.vadEngine)
                    .put("engine_version", EfficientVad.ENGINE_VERSION)
                    .put("mode", EfficientVad.MODE)
                    .put("frame_ms", EfficientVad.FRAME_MS)
                    .put("config_version", EfficientVad.CONFIG_VERSION),
            )
        }
        return body
    }

    fun uploadChunk(chunk: ChunkRecord): ChunkUploadReceipt {
        val response = http.upload(
            "$API_ROOT/sessions/${chunk.sessionId}/chunks/${chunk.chunkIndex}",
            File(chunk.path),
            chunk.mimeType,
            mapOf(
                "X-Chunk-SHA256" to chunk.sha256,
                "X-Chunk-Duration-Ms" to chunk.durationMs.coerceAtLeast(1L).toString(),
                "X-Audio-Start-Ms" to chunk.startMs.toString(),
                "X-Audio-End-Ms" to chunk.endMs.toString(),
                "X-Wall-Start-Ms" to chunk.wallStartMs.toString(),
                "X-Wall-End-Ms" to chunk.wallEndMs.toString(),
            ),
        ).requireV2Session(chunk.sessionId)
        val receiptMatches =
            response.optBoolean("accepted", false) &&
                response.optInt("chunk_index", -1) == chunk.chunkIndex &&
                response.optString("sha256") == chunk.sha256 &&
                response.optLong("duration_ms", -1L) == chunk.durationMs
        if (!receiptMatches) throw contractMismatch("chunk_receipt_mismatch")
        return ChunkUploadReceipt(
            chunkIndex = chunk.chunkIndex,
            accepted = true,
            duplicate = response.optBoolean("duplicate", false),
            chunksReceived = response.optInt("chunks_received", 0),
            bytesReceived = response.optLong("bytes_received", 0L),
        )
    }

    fun completeSession(session: SessionSnapshot, chunks: List<ChunkRecord>): RemoteProgress {
        val ordered = requireOrderedChunks(session, chunks)
        val manifest = JSONArray()
        ordered.forEach { chunk ->
            manifest.put(
                JSONObject()
                    .put("chunk_index", chunk.chunkIndex)
                    .put("sha256", chunk.sha256)
                    .put("duration_ms", chunk.durationMs)
                    .put("audio_start_ms", chunk.startMs)
                    .put("audio_end_ms", chunk.endMs)
                    .put("wall_start_ms", chunk.wallStartMs)
                    .put("wall_end_ms", chunk.wallEndMs),
            )
        }
        return http.jsonRequest(
            "POST", "$API_ROOT/sessions/${session.sessionId}/complete", JSONObject()
                .put("ended_at", requireNotNull(session.endedAt))
                .put("wall_elapsed_ms", session.wallElapsedMs)
                .put("manual_pause_ms", session.manualPauseMs)
                .put("recorded_audio_ms", session.durationMs)
                .put("auto_silence_skipped_ms", session.autoSilenceSkippedMs)
                .put("chunk_count", session.chunkCount)
                .put("chunks", manifest)
                .toBytes(),
        ).requireV2Session(session.sessionId).toProgressV2()
    }

    fun status(sessionId: String): RemoteProgress =
        http.jsonRequest("GET", "$API_ROOT/sessions/$sessionId", null)
            .requireV2Session(sessionId)
            .toProgressV2()

    companion object {
        private const val API_ROOT = "/voice-intake/v2"
    }
}

private class VoiceHttpClient(
    baseUrl: String, private val token: String, private val cancellation: TransferCancellation,
) {
    private val serviceBaseUrl = VoiceIntakeV2Policy.normalizeServiceBaseUrl(baseUrl)

    fun jsonRequest(method: String, path: String, body: ByteArray?): JSONObject =
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

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(serviceBaseUrl + path).openConnection() as HttpURLConnection).apply {
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
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(httpCode: Int, text: String): ApiException {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val detail = root?.optJSONObject("detail")
        val code = detail?.optString("code")?.takeIf { it.isNotBlank() }
            ?: root?.optString("detail")?.takeIf { it.isNotBlank() }
            ?: "http_$httpCode"
        val retryable = detail?.optBoolean("retryable", httpCode == 429 || httpCode >= 500)
            ?: (httpCode == 429 || httpCode >= 500)
        val retryAfter = detail?.let {
            if (it.has("retry_after_seconds") && !it.isNull("retry_after_seconds")) {
                it.optInt("retry_after_seconds").takeIf { value -> value > 0 }
            } else {
                null
            }
        }
        val reconcile = detail?.optBoolean("reconciliation_required", false) ?: false
        return ApiException(
            humanMessage(code, retryAfter, reconcile),
            code,
            retryable,
            retryAfter,
            reconcile,
        )
    }

    private fun humanMessage(code: String, retry: Int?, reconcile: Boolean): String {
        val wait = retry?.let { " Повтор не раньше чем через ${formatWait(it)}." }.orEmpty()
        return when {
            reconcile || VoiceIntakeV2Policy.requiresManualReconciliation(code, false) ->
                "Нужна безопасная сверка с сервером; аудио сохранено на телефоне."
            VoiceIntakeV2Policy.isQuotaCode(code) ->
                "Доступный лимит Gemini временно исчерпан.$wait Аудио сохранено на телефоне."
            code.contains("limiter") || code.contains("reservation") ->
                "Общий контроль лимитов временно недоступен.$wait Аудио сохранено на телефоне."
            code.startsWith("provider_") ->
                "Gemini не завершил обработку.$wait Аудио сохранено."
            code.startsWith("github_") || code.startsWith("idea_hub_") ->
                "Расшифровка готова, но GitHub ещё не подтверждён.$wait"
            code == "chunks_missing" ->
                "Серверу не хватает одного или нескольких сегментов; они будут проверены повторно."
            code == "session_not_created" || code.contains("session_not_initialized") ||
                code.contains("terminology_not_initialized") ->
                "Серверная сессия будет безопасно создана повторно."
            code == "device_token_required" || code == "device_token_invalid" ->
                "Проверьте device token в настройках; аудио сохранено локально."
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
}

private fun requireOrderedChunks(session: SessionSnapshot, chunks: List<ChunkRecord>): List<ChunkRecord> {
    require(chunks.isNotEmpty()) { "cannot complete a session without chunks" }
    require(chunks.size == session.chunkCount) { "local chunk count changed before completion" }
    return chunks.sortedBy { it.chunkIndex }.also { ordered ->
        require(ordered.map { it.chunkIndex } == ordered.indices.toList()) {
            "chunks must be contiguous"
        }
        if (session.protocolVersion >= 2) {
            require(ordered.first().startMs == 0L) { "audio timeline must start at zero" }
            ordered.forEach { chunk ->
                require(chunk.durationMs > 0L) { "chunk duration must be positive" }
                require(chunk.wallEndMs > chunk.wallStartMs) { "chunk wall range must increase" }
            }
            ordered.zipWithNext().forEach { (previous, current) ->
                require(current.startMs == previous.endMs) { "audio timeline must be contiguous" }
                require(
                    VoiceIntakeV2Policy.wallTimelineFollows(
                        previous.wallEndMs,
                        current.wallStartMs,
                    ),
                ) { "wall timeline overlap exceeds capture-clock tolerance" }
            }
            require(ordered.last().endMs == session.durationMs) {
                "audio timeline must match recorded duration"
            }
            require(ordered.last().wallEndMs <= session.wallElapsedMs) {
                "wall timeline must not exceed session elapsed time"
            }
            require(
                session.durationMs + session.manualPauseMs + session.autoSilenceSkippedMs ==
                    session.wallElapsedMs,
            ) { "session duration accounting must balance" }
        }
    }
}

private fun JSONObject.requireV2Session(expectedSessionId: String): JSONObject {
    if (optString("api_version") != "2.0") throw contractMismatch("api_version_mismatch")
    if (optString("session_id") != expectedSessionId) throw contractMismatch("session_receipt_mismatch")
    return this
}

private fun contractMismatch(code: String): Nothing = throw contractMismatchException(code)

private fun contractMismatchException(code: String) = ApiException(
    message = "Ответ Voice Intake v2 не совпадает с закреплённым контрактом; аудио сохранено.",
    code = code,
    retryable = false,
    retryAfterSeconds = null,
    reconciliationRequired = true,
)

private fun JSONObject.toBytes() = toString().toByteArray(StandardCharsets.UTF_8)

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun retryAfterFromTimestamp(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val epoch = runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrNull() ?: return null
    val remainingMs = epoch - System.currentTimeMillis()
    if (remainingMs <= 0L) return 0
    return ceil(remainingMs / 1000.0)
        .coerceAtMost(Int.MAX_VALUE.toDouble())
        .toInt()
        .coerceAtLeast(1)
}

private fun JSONObject.toProgressV1(): RemoteProgress {
    val verified = optBoolean("github_verified", false)
    return RemoteProgress(
        state = optString("state", RemoteState.PROCESSING),
        recordingFinished = optBoolean("recording_finished", false),
        chunksExpected = nullableInt("chunks_expected"),
        chunksUploaded = optInt("chunks_uploaded", 0),
        chunksTranscribed = optInt("chunks_transcribed", 0),
        geminiRequestsTotal = 0,
        geminiRequestsCompleted = 0,
        transcriptionComplete = verified,
        summaryComplete = verified,
        githubVerified = verified,
        serverAudioPurged = verified,
        githubUrl = nullableString("github_url"),
        githubCommitSha = nullableString("github_commit_sha"),
        lastError = nullableString("last_error"),
        errorCode = null,
        retryable = false,
        retryAfterSeconds = nullableInt("retry_after_seconds"),
        reconciliationRequired = false,
    )
}

private fun JSONObject.toProgressV2(): RemoteProgress {
    val transcriptionComplete = optBoolean("transcription_complete", false)
    val expected = optInt("chunks_expected", optInt("chunks_received", 0))
    val verified = optBoolean("github_verified", false)
    val purged = optBoolean("server_audio_purged", false)
    val rawState = optString("state", RemoteState.RECEIVING)
    val safeState = if (rawState == RemoteState.PUBLISHED_VERIFIED && (!verified || !purged)) {
        RemoteState.VERIFYING
    } else {
        rawState
    }
    val retryAfter = nullableInt("retry_after_seconds")
        ?: retryAfterFromTimestamp(nullableString("retry_at"))
    return RemoteProgress(
        state = safeState,
        recordingFinished = optBoolean("recording_finished", false),
        chunksExpected = nullableInt("chunks_expected"),
        chunksUploaded = optInt("chunks_received", optInt("chunks_uploaded", 0)),
        chunksTranscribed = if (transcriptionComplete) expected else 0,
        geminiRequestsTotal = optInt("gemini_requests_total", 2),
        geminiRequestsCompleted = optInt("gemini_requests_completed", 0),
        transcriptionComplete = transcriptionComplete,
        summaryComplete = optBoolean("summary_complete", false),
        githubVerified = verified,
        serverAudioPurged = purged,
        githubUrl = nullableString("github_url"),
        githubCommitSha = nullableString("github_commit_sha"),
        lastError = null,
        errorCode = nullableString("error_code"),
        retryable = optBoolean("retryable", false),
        retryAfterSeconds = retryAfter,
        reconciliationRequired = optBoolean("reconciliation_required", false),
    )
}

class ApiException(
    message: String,
    val code: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int?,
    val reconciliationRequired: Boolean,
) : Exception(message)
