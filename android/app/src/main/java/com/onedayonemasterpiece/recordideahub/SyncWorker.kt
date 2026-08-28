package com.onedayonemasterpiece.recordideahub

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val UNIQUE_WORK = "record-idea-hub-sync"

    fun enqueue(context: Context, delaySeconds: Long = 0L) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
        if (delaySeconds > 0L) builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            builder.build(),
        )
    }
}

class SyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = AppGraph.config(applicationContext)
        val serverUrl = config.backendUrl
        val deviceToken = config.deviceToken
        if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            return@withContext Result.success()
        }

        val store = AppGraph.store(applicationContext)
        if (!store.deleteAllVerifiedAudio()) {
            SyncScheduler.enqueue(applicationContext, LOCAL_PURGE_RETRY_SECONDS)
        }
        for (session in store.sessionsNeedingSync()) {
            try {
                if (session.protocolVersion >= 2) {
                    syncV2(store, ApiClientV2(serverUrl, deviceToken), session)
                } else {
                    syncLegacyV1(store, ApiClientV1(serverUrl, deviceToken), session)
                }
            } catch (exc: ApiException) {
                handleApiFailure(store, session.sessionId, exc)
            } catch (exc: IllegalArgumentException) {
                store.setRemoteState(
                    session.sessionId,
                    RemoteState.RECONCILIATION_REQUIRED,
                    exc.message ?: "Локальные данные не совпадают с контрактом; аудио сохранено",
                )
            } catch (exc: IllegalStateException) {
                store.setRemoteState(
                    session.sessionId,
                    RemoteState.RECONCILIATION_REQUIRED,
                    exc.message ?: "Нужна безопасная сверка локальной очереди; аудио сохранено",
                )
            } catch (exc: Exception) {
                store.setRetryableError(
                    session.sessionId,
                    NETWORK_RETRY_SECONDS,
                    exc.message ?: "Локальная очередь сохранена; повтор будет выполнен автоматически",
                )
                SyncScheduler.enqueue(applicationContext, NETWORK_RETRY_SECONDS)
            }
        }
        Result.success()
    }

    private fun syncV2(store: SessionStore, api: ApiClientV2, original: SessionSnapshot) {
        // Authoritative v2 rule: every pass starts with durable idempotent create/re-open.
        val initialized = api.createSession(original)
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
            return
        }

        var session = store.session(original.sessionId) ?: return
        if (initialized.recordingFinished) {
            val progress = if (VoiceIntakeV2Policy.shouldRetryComplete(initialized)) {
                api.completeSession(session, store.chunks(session.sessionId))
            } else {
                initialized
            }
            store.updateRemoteProgress(session.sessionId, progress)
            handleV2Progress(store, session.sessionId, progress)
            return
        }

        for (chunk in store.pendingChunks(session)) {
            store.setRemoteState(session.sessionId, RemoteState.RECEIVING)
            api.uploadChunk(chunk)
            store.markChunkUploaded(session.sessionId, chunk.chunkIndex)
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
            SyncScheduler.enqueue(applicationContext, UPLOAD_RETRY_SECONDS)
            return
        }

        val progress = if (!session.completeSent) {
            api.completeSession(session, chunks).also { store.markCompleteSent(session.sessionId) }
        } else {
            val current = api.status(session.sessionId)
            if (VoiceIntakeV2Policy.shouldRetryComplete(current)) {
                api.completeSession(session, chunks)
            } else {
                current
            }
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
                SyncScheduler.enqueue(applicationContext, delay)
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
                SyncScheduler.enqueue(applicationContext, delay)
            }
            progress.state == RemoteState.RETRYABLE_ERROR -> {
                store.setRemoteState(
                    sessionId,
                    RemoteState.RECONCILIATION_REQUIRED,
                    progress.errorCode ?: "Требуется исправить или сверить данные сессии",
                )
            }
            else -> {
                val delay = store.nextPollDelaySeconds(sessionId)
                SyncScheduler.enqueue(applicationContext, delay)
            }
        }
    }

    private fun deleteLocalAudioOrRetry(store: SessionStore, sessionId: String) {
        if (!store.deleteVerifiedAudio(sessionId)) {
            SyncScheduler.enqueue(applicationContext, LOCAL_PURGE_RETRY_SECONDS)
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
            SyncScheduler.enqueue(applicationContext, LEGACY_POLL_SECONDS)
            return
        }
        store.setRemoteState(session.sessionId, RemoteState.PUBLISHING)
        val progress = api.completeSession(session, chunks)
        store.updateRemoteProgress(session.sessionId, progress)
        if (progress.isVerifiedAndPurged()) {
            deleteLocalAudioOrRetry(store, session.sessionId)
        } else {
            SyncScheduler.enqueue(applicationContext, LEGACY_POLL_SECONDS)
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
            SyncScheduler.enqueue(applicationContext, CREATE_RETRY_SECONDS)
            return
        }
        if (VoiceIntakeV2Policy.requiresManualReconciliation(exc.code, exc.reconciliationRequired)) {
            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
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
                SyncScheduler.enqueue(applicationContext, UPLOAD_RETRY_SECONDS)
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
        SyncScheduler.enqueue(applicationContext, delay)
    }

    private fun RemoteProgress.isVerifiedAndPurged(): Boolean =
        githubVerified && serverAudioPurged

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
