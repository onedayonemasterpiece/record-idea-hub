package com.onedayonemasterpiece.recordideahub

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

    private fun syncV2(store: SessionStore, api: ApiClientV2, original: SessionSnapshot) {
        // Authoritative v2 rule: every pass starts with durable idempotent create/re-open.
        val initialized = if (original.remoteState == RemoteState.RECONCILIATION_REQUIRED) {
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
        }
        if (!store.reconcileV2ServerState(
                original.sessionId,
                initialized.chunksUploaded,
                initialized.recordingFinished,
            )
        ) {
            store.setRemoteState(
                original.sessionId,
                RemoteState.RECONCILIATION_REQUIRED,
                "Серверный реестр сегментов не совпадает с локальной сессией; аудио сохранено",
            )
            schedule(300L)
            return
        }
        store.markServerInitialized(original.sessionId)
        store.updateRemoteProgress(original.sessionId, initialized)

        if (initialized.isVerifiedAndPurged()) {
            deleteLocalAudioOrRetry(store, original.sessionId)
            return
        }
        if (initialized.reconciliationRequired ||
            initialized.state == RemoteState.RECONCILIATION_REQUIRED
        ) {
            store.setRemoteState(
                original.sessionId,
                RemoteState.RECONCILIATION_REQUIRED,
                initialized.errorCode ?: "Сервер требует безопасную сверку; аудио сохранено",
            )
            schedule(300L)
            return
        }

        var session = store.session(original.sessionId) ?: return
        if (!DeliveryPolicy.maySendComplete(initialized.recordingFinished)) {
            // Once complete is accepted the server owns processing and retries.
            val progress = initialized
            store.updateRemoteProgress(session.sessionId, progress)
            handleV2Progress(store, session.sessionId, progress)
            return
        }

        for (chunk in store.pendingChunks(session)) {
            store.setRemoteState(session.sessionId, RemoteState.RECEIVING)
            cancellation.check()
            api.uploadChunk(chunk)
            store.markChunkUploaded(session.sessionId, chunk.chunkIndex)
            onProgress()
        }

        session = store.session(session.sessionId) ?: return
        if (session.captureState != CaptureState.FINISHED || session.chunkCount <= 0) return
        val chunks = store.chunks(session.sessionId)
        check(chunks.size == session.chunkCount) {
            "local chunk count changed before v2 completion"
        }
        if (chunks.any { !it.uploaded }) {
            store.setRetryableError(
                session.sessionId,
                UPLOAD_RETRY_SECONDS,
                "Не все сегменты получили durable receipt; повтор запланирован",
            )
            schedule(UPLOAD_RETRY_SECONDS)
            return
        }

        val progress = if (!session.completeSent) {
            api.completeSession(session, chunks).also { store.markCompleteSent(session.sessionId) }
        } else {
            val current = api.status(session.sessionId)
            if (DeliveryPolicy.maySendComplete(current.recordingFinished)) api.completeSession(session, chunks)
            else current
        }
        store.updateRemoteProgress(session.sessionId, progress)
        handleV2Progress(store, session.sessionId, progress)
    }

    private fun handleV2Progress(
        store: SessionStore,
        sessionId: String,
        progress: RemoteProgress,
    ) {
        when {
            progress.isVerifiedAndPurged() -> deleteLocalAudioOrRetry(store, sessionId)
            progress.reconciliationRequired ||
                progress.state == RemoteState.RECONCILIATION_REQUIRED ||
                VoiceIntakeV2Policy.requiresManualReconciliation(
                    progress.errorCode.orEmpty(),
                    progress.reconciliationRequired,
                ) -> {
                store.setRemoteState(
                    sessionId,
                    RemoteState.RECONCILIATION_REQUIRED,
                    progress.errorCode ?: "Сервер требует безопасную сверку; аудио сохранено",
                )
                schedule(300L)
            }
            progress.state == RemoteState.WAITING_FOR_QUOTA -> {
                val delay = VoiceIntakeV2Policy.retryDelaySeconds(
                    progress.retryAfterSeconds,
                    QUOTA_FALLBACK_SECONDS,
                )
                store.setQuotaWait(
                    sessionId,
                    delay,
                    progress.errorCode ?: "Ожидание доступного лимита Gemini",
                )
                schedule(delay)
            }
            progress.state == RemoteState.RETRYABLE_ERROR && progress.retryable -> {
                val delay = VoiceIntakeV2Policy.retryDelaySeconds(
                    progress.retryAfterSeconds,
                    PROCESSING_RETRY_SECONDS,
                )
                store.setRetryableError(
                    sessionId,
                    delay,
                    progress.errorCode ?: "Сервер временно не завершил обработку",
                )
                schedule(delay)
            }
            progress.state == RemoteState.RETRYABLE_ERROR -> {
                store.setRemoteState(
                    sessionId,
                    RemoteState.RECONCILIATION_REQUIRED,
                    progress.errorCode ?: "Требуется исправить или сверить данные сессии",
                )
                schedule(300L)
            }
            else -> {
                val delay = store.nextPollDelaySeconds(sessionId)
                schedule(delay)
            }
        }
    }

    private fun deleteLocalAudioOrRetry(store: SessionStore, sessionId: String) {
        if (!store.deleteVerifiedAudio(sessionId)) {
            schedule(LOCAL_PURGE_RETRY_SECONDS)
        }
    }

    private fun syncLegacyV1(store: SessionStore, api: ApiClientV1, original: SessionSnapshot) {
        // Compatibility repair for unfinished v1 rows; the v1 wire contract remains unchanged.
        val initialized = api.createSession(original)
        store.markServerInitialized(original.sessionId)
        store.updateRemoteProgress(original.sessionId, initialized)
        if (initialized.isVerifiedAndPurged()) {
            deleteLocalAudioOrRetry(store, original.sessionId)
            return
        }

        var session = store.session(original.sessionId) ?: return
        if (session.captureState == CaptureState.FINISHED) {
            val reconciled = api.status(session.sessionId)
            store.updateRemoteProgress(session.sessionId, reconciled)
            if (reconciled.isVerifiedAndPurged()) {
                deleteLocalAudioOrRetry(store, session.sessionId)
                return
            }
        }
        for (chunk in store.pendingChunks(session)) {
            store.setRemoteState(session.sessionId, RemoteState.PROCESSING)
            store.saveChunkTranscript(
                session.sessionId,
                chunk.chunkIndex,
                api.uploadChunk(chunk),
            )
        }
        session = store.session(session.sessionId) ?: return
        if (session.captureState != CaptureState.FINISHED || session.chunkCount <= 0) return
        val chunks = store.chunks(session.sessionId)
        if (chunks.any { !it.transcribed }) {
            schedule(LEGACY_POLL_SECONDS)
            return
        }
        store.setRemoteState(session.sessionId, RemoteState.PUBLISHING)
        val progress = api.completeSession(session, chunks)
        store.updateRemoteProgress(session.sessionId, progress)
        if (progress.isVerifiedAndPurged()) {
            deleteLocalAudioOrRetry(store, session.sessionId)
        } else {
            schedule(LEGACY_POLL_SECONDS)
        }
    }

    private fun handleApiFailure(store: SessionStore, sessionId: String, exc: ApiException) {
        if (
            exc.code == "session_not_created" ||
            exc.code.contains("session_not_initialized") ||
            exc.code.contains("terminology_not_initialized")
        ) {
            store.setRetryableError(
                sessionId,
                CREATE_RETRY_SECONDS,
                exc.message ?: "Серверная сессия будет создана повторно",
            )
            schedule(CREATE_RETRY_SECONDS)
            return
        }
        if (VoiceIntakeV2Policy.requiresManualReconciliation(exc.code, exc.reconciliationRequired)) {
            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
            schedule(300L)
            return
        }
        when (exc.code) {
            "chunks_missing" -> {
                store.markAllV2ChunksPending(sessionId)
                store.setRetryableError(
                    sessionId,
                    UPLOAD_RETRY_SECONDS,
                    exc.message ?: "Сервер запросил повторную проверку сегментов",
                )
                schedule(UPLOAD_RETRY_SECONDS)
                return
            }
            "device_token_required", "device_token_invalid" -> {
                store.setRemoteState(sessionId, RemoteState.RETRYABLE_ERROR, exc.message)
                return
            }
        }
        if (!exc.retryable) {
            store.setRemoteState(
                sessionId,
                RemoteState.RECONCILIATION_REQUIRED,
                exc.message ?: "Сервер отклонил неизменяемые данные; аудио сохранено",
            )
            return
        }

        val delay = VoiceIntakeV2Policy.retryDelaySeconds(
            exc.retryAfterSeconds,
            if (VoiceIntakeV2Policy.isQuotaCode(exc.code)) {
                QUOTA_FALLBACK_SECONDS
            } else {
                NETWORK_RETRY_SECONDS
            },
        )
        if (VoiceIntakeV2Policy.isQuotaCode(exc.code)) {
            store.setQuotaWait(
                sessionId,
                delay,
                exc.message ?: "Ожидание доступного лимита Gemini",
            )
        } else {
            store.setRetryableError(sessionId, delay, exc.message)
        }
        schedule(delay)
    }

    private fun RemoteProgress.isVerifiedAndPurged(): Boolean =
        DeliveryPolicy.mayDeleteAudio(state, githubVerified, serverAudioPurged)

    companion object {
        private const val CREATE_RETRY_SECONDS = 5L
        private const val UPLOAD_RETRY_SECONDS = 15L
        private const val NETWORK_RETRY_SECONDS = 30L
        private const val PROCESSING_RETRY_SECONDS = 30L
        private const val QUOTA_FALLBACK_SECONDS = 60L
        private const val LOCAL_PURGE_RETRY_SECONDS = 60L
        private const val LEGACY_POLL_SECONDS = 15L
    }
}
