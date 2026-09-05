package com.onedayonemasterpiece.recordideahub

/** Only code metadata. No body, URL, token, message, cause, arguments or local values. */
class SyncTrace {
    internal var operation = "sync"
    internal var phase = "start"
    internal var httpStatus: Int? = null

    internal fun begin(name: String) {
        operation = name
        phase = "start"
        httpStatus = null
    }

    internal fun describe(error: Exception): String {
        val type = identifier(error.javaClass.name)
        val code = (error as? ApiException)?.code?.let { knownCode(it) } ?: "none"
        val frames = error.stackTrace.asSequence()
            .filter { it.className.startsWith("com.onedayonemasterpiece.recordideahub.") }
            .take(4)
            .joinToString(" > ") {
                "${identifier(it.className.substringAfterLast('.'))}.${identifier(it.methodName)}:${it.lineNumber}"
            }
        return "stage=$operation/$phase; type=$type; http=${httpStatus ?: "unknown"}; code=$code; at=$frames"
            .take(700)
    }

    private fun identifier(value: String) = value.replace(Regex("[^A-Za-z0-9_.$<>-]"), "_").take(120)

    private fun knownCode(value: String): String = when {
        value in setOf("chunks_missing", "session_not_created", "session_not_found", "device_token_required",
            "device_token_invalid", "api_version_mismatch", "session_receipt_mismatch", "chunk_receipt_mismatch",
            "session_payload_conflict", "session_invalid", "complete_manifest_invalid",
            "voice_intake_v2_worker_unavailable", "voice_intake_v2_disabled", "voice_intake_disabled",
            "terminology_unavailable", "terminology_resolver_unavailable", "provider_outcome_ambiguous") -> value
        value.matches(Regex("http_[1-5][0-9]{2}")) -> value
        else -> "unclassified"
    }
}
