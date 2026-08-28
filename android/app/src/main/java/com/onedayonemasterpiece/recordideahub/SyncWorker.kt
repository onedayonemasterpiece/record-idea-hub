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
        var currentSessionId: String? = null
        try {
            store.deleteAllVerifiedAudio()
            for (session in store.sessionsNeedingSync()) {
                currentSessionId = session.sessionId
                if (session.protocolVersion >= 2) {
                    syncV2(store, ApiClientV2(serverUrl, deviceToken), session)
                } else {
                    syncLegacyV1(store, ApiClientV1(serverUrl, deviceToken), session)
                }
            }
            Result.success()
        } catch (exc: ApiException) {
            currentSessionId?.let { handleApiFailure(store, it, exc) }
            Result.success()
        } catch (exc: Exception) {
            currentSessionId?.let { sessionId ->
                store.setRemoteState(
                    sessionId,
                    RemoteState.RETRYABLE_ERROR,
                    exc.message ?: "Локальная очередь сохранена; повтор будет выполнен автоматически",
                )
            }
            SyncScheduler.enqueue(applicationContext, 30L)
            Result.success()
        }
    }

    private fun syncV2(store: SessionStore, api: ApiClientV2, original: SessionSnapshot) {
        // Required on every pass. This repairs the server-restart defect found in the v1 client:
        // a local session is not proof that the server still has its durable terminology identity.
        val initialized = api.createSession(original)
        store.markServerInitialized(original.sessionId)
        store.updateRemoteProgress(original.sessionId, initialized)
        if (initialized.isVerifiedAndPurged()) {
            store.deleteVerifiedAudio(original.sessionId)
            return
        }

        var session = store.session(original.sessionId) ?: return
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
            SyncScheduler.enqueue(applicationContext, 15L)
            return
        }

        val progress = if (!session.completeSent) {
            api.completeSession(session, chunks).also { store.markCompleteSent(session.sessionId) }
        } else {
            api.status(session.sessionId)
        }
        store.updateRemoteProgress(session.sessionId, progress)
        when {
            progress.isVerifiedAndPurged() -> store.deleteVerifiedAudio(session.sessionId)
            progress.reconciliationRequired || progress.state == RemoteState.RECONCILIATION_REQUIRED -> {
                store.setRemoteState(
                    session.sessionId,
                    RemoteState.RECONCILIATION_REQUIRED,
                    "Сервер сверяет уже отправленный запрос; аудио сохранено",
                )
            }
            progress.state == RemoteState.WAITING_FOR_QUOTA -> {
                val delay = (progress.retryAfterSeconds ?: 60).coerceIn(1, 86_400)
                store.setQuotaWait(
                    session.sessionId,
                    delay,
                    progress.errorCode ?: "Ожидание доступного лимита Gemini",
                )
                SyncScheduler.enqueue(applicationContext, delay.toLong())
            }
            progress.state == RemoteState.RETRYABLE_ERROR && progress.retryable -> {
                val delay = (progress.retryAfterSeconds ?: 30).coerceIn(1, 86_400)
                store.setRemoteState(
                    session.sessionId,
                    RemoteState.RETRYABLE_ERROR,
                    progress.errorCode ?: "Сервер временно не завершил обработку",
                )
                SyncScheduler.enqueue(applicationContext, delay.toLong())
            }
            progress.state == RemoteState.RETRYABLE_ERROR -> {
                store.setRemoteState(
                    session.sessionId,
                    RemoteState.RETRYABLE_ERROR,
                    progress.errorCode ?: "Требуется исправить данные сессии",
                )
            }
            else -> SyncScheduler.enqueue(
                applicationContext,
                store.nextPollDelaySeconds(session.sessionId),
            )
        }
    }

    private fun syncLegacyV1(store: SessionStore, api: ApiClientV1, original: SessionSnapshot) {
        // The original APK omitted this call. Keep the compatibility fix for unfinished v1 rows.
        val initialized = api.createSession(original)
        store.markServerInitialized(original.sessionId)
        store.updateRemoteProgress(original.sessionId, initialized)
        if (initialized.isVerifiedAndPurged()) {
            store.deleteVerifiedAudio(original.sessionId)
            return
        }

        var session = store.session(original.sessionId) ?: return
        if (session.captureState == CaptureState.FINISHED) {
            val reconciled = api.status(session.sessionId)
            store.updateRemoteProgress(session.sessionId, reconciled)
            if (reconciled.isVerifiedAndPurged()) {
                store.deleteVerifiedAudio(session.sessionId)
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
            SyncScheduler.enqueue(applicationContext, 15L)
            return
        }
        store.setRemoteState(session.sessionId, RemoteState.PUBLISHING)
        val progress = api.completeSession(session, chunks)
        store.updateRemoteProgress(session.sessionId, progress)
        if (progress.isVerifiedAndPurged()) {
            store.deleteVerifiedAudio(session.sessionId)
        } else {
            SyncScheduler.enqueue(applicationContext, 15L)
        }
    }

    private fun handleApiFailure(store: SessionStore, sessionId: String, exc: ApiException) {
        if (exc.reconciliationRequired) {
            store.setRemoteState(sessionId, RemoteState.RECONCILIATION_REQUIRED, exc.message)
            return
        }
        if (exc.code == "session_not_created" || exc.code.contains("session_not_initialized")) {
            store.setRemoteState(
                sessionId,
                RemoteState.RETRYABLE_ERROR,
                exc.message ?: "Серверная сессия будет создана повторно",
            )
            SyncScheduler.enqueue(applicationContext, 5L)
            return
        }
        if (!exc.retryable) {
            store.setRemoteState(sessionId, RemoteState.RETRYABLE_ERROR, exc.message)
            return
        }
        val delay = (exc.retryAfterSeconds ?: 30).coerceIn(1, 86_400)
        if (
            exc.code == "google_quota_wait" ||
            exc.code == "provider_429" ||
            exc.code.startsWith("quota_exhausted_")
        ) {
            store.setQuotaWait(
                sessionId,
                delay,
                exc.message ?: "Ожидание доступного лимита Gemini",
            )
        } else {
            store.setRemoteState(sessionId, RemoteState.RETRYABLE_ERROR, exc.message)
        }
        SyncScheduler.enqueue(applicationContext, delay.toLong())
    }

    private fun RemoteProgress.isVerifiedAndPurged(): Boolean =
        githubVerified && serverAudioPurged
}
