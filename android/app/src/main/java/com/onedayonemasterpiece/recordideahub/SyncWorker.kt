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

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

class SyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = AppGraph.config(applicationContext)
        val backendUrl = config.backendUrl
        val deviceToken = config.deviceToken
        if (backendUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) return@withContext Result.success()

        val store = AppGraph.store(applicationContext)
        val api = ApiClient(backendUrl, deviceToken)
        var waitingForFinalResult = false
        var currentSessionId: String? = null
        try {
            store.deleteAllVerifiedAudio()
            for (session in store.sessionsNeedingSync()) {
                currentSessionId = session.sessionId
                var progress = api.createSession(session)
                store.updateRemoteProgress(session.sessionId, progress)

                for (chunk in store.chunks(session.sessionId, pendingOnly = true)) {
                    api.uploadChunk(chunk)
                    store.markChunkUploaded(session.sessionId, chunk.chunkIndex)
                }

                val refreshed = store.session(session.sessionId) ?: continue
                progress = if (refreshed.captureState == CaptureState.FINISHED) {
                    if (refreshed.chunkCount <= 0) continue
                    api.completeSession(refreshed)
                } else {
                    api.status(refreshed.sessionId)
                }
                store.updateRemoteProgress(refreshed.sessionId, progress)

                if (progress.githubVerified) {
                    store.deleteVerifiedAudio(refreshed.sessionId)
                } else if (refreshed.captureState == CaptureState.FINISHED) {
                    waitingForFinalResult = true
                }
            }
            if (waitingForFinalResult) Result.retry() else Result.success()
        } catch (exc: ApiException) {
            currentSessionId?.let { store.setLocalError(it, exc.message) }
            if (exc.retryable) Result.retry() else Result.failure()
        } catch (exc: Exception) {
            currentSessionId?.let { store.setLocalError(it, exc.message) }
            Result.retry()
        }
    }
}
