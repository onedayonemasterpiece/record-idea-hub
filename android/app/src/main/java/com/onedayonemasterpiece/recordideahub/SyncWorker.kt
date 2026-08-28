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
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
        if (delaySeconds > 0L) {
            builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
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
        val api = ApiClient(serverUrl, deviceToken)
        var currentSessionId: String? = null
        try {
            store.deleteAllVerifiedAudio()
            for (session in store.sessionsNeedingSync()) {
                currentSessionId = session.sessionId

                if (session.captureState == CaptureState.FINISHED) {
                    val reconciled = api.status(session.sessionId)
                    store.updateRemoteProgress(session.sessionId, reconciled)
                    if (reconciled.githubVerified) {
                        store.deleteVerifiedAudio(session.sessionId)
                        continue
                    }
                }

                for (chunk in store.chunks(session.sessionId, pendingOnly = true)) {
                    store.setRemoteState(session.sessionId, RemoteState.PROCESSING)
                    val transcript = api.uploadChunk(chunk)
                    store.saveChunkTranscript(session.sessionId, chunk.chunkIndex, transcript)
                }

                val refreshed = store.session(session.sessionId) ?: continue
                if (refreshed.captureState != CaptureState.FINISHED) continue
                if (refreshed.chunkCount <= 0) continue

                val chunks = store.chunks(refreshed.sessionId)
                check(chunks.size == refreshed.chunkCount) {
                    "local session chunk count changed before publication"
                }
                if (chunks.any { !it.transcribed }) {
                    SyncScheduler.enqueue(applicationContext, 10L)
                    continue
                }

                store.setRemoteState(refreshed.sessionId, RemoteState.PUBLISHING)
                val progress = api.completeSession(refreshed, chunks)
                store.updateRemoteProgress(refreshed.sessionId, progress)
                if (progress.githubVerified) {
                    store.deleteVerifiedAudio(refreshed.sessionId)
                } else {
                    SyncScheduler.enqueue(applicationContext, 15L)
                }
            }
            Result.success()
        } catch (exc: ApiException) {
            val sessionId = currentSessionId
            if (sessionId != null) {
                if (exc.retryable) {
                    val delay = (exc.retryAfterSeconds ?: if (exc.reconciliationRequired) 15 else 30)
                        .coerceIn(1, 86_400)
                    if (exc.code == "provider_429" || exc.code.startsWith("quota_exhausted_")) {
                        store.setQuotaWait(
                            sessionId,
                            delay,
                            exc.message ?: "Ожидание доступного лимита Gemini",
                        )
                    } else {
                        store.setRemoteState(
                            sessionId,
                            RemoteState.RETRYABLE_ERROR,
                            exc.message,
                        )
                    }
                    SyncScheduler.enqueue(applicationContext, delay.toLong())
                } else {
                    store.setRemoteState(sessionId, RemoteState.RETRYABLE_ERROR, exc.message)
                }
            }
            if (exc.retryable) Result.success() else Result.failure()
        } catch (exc: Exception) {
            currentSessionId?.let { sessionId ->
                store.setRemoteState(
                    sessionId,
                    RemoteState.RETRYABLE_ERROR,
                    exc.message ?: "Локальная очередь сохранена; повтор будет выполнен автоматически",
                )
            }
            Result.retry()
        }
    }
}
