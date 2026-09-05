package com.onedayonemasterpiece.recordideahub

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val SESSION_ID = "session_id"
    private const val INTERACTIVE = "interactive_transfer"

    fun initialize(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("record-idea-hub-sync")
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "voice-delivery-watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWatchdog>(15, TimeUnit.MINUTES).build(),
        )
        enqueue(context)
    }

    fun enqueue(context: Context, delaySeconds: Long = 0L) {
        for (session in AppGraph.store(context).sessionsNeedingSync(Long.MAX_VALUE)) {
            val due = session.retryAtEpochMs?.let { VoiceIntakeV2Policy.delaySecondsUntil(it) } ?: 0L
            schedule(context, session.sessionId, maxOf(delaySeconds, due), append = false)
        }
    }

    fun next(context: Context, sessionId: String, delaySeconds: Long) =
        schedule(context, sessionId, delaySeconds, append = true)

    fun userTransfer(context: Context, sessionId: String) {
        // A durable fallback exists even if the process dies while scheduling UIDT.
        schedule(context, sessionId, 60L, append = false)
        if (Build.VERSION.SDK_INT >= 34 && TransferJobService.schedule(context, sessionId)) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SESSION_ID to sessionId, INTERACTIVE to true))
            .setConstraints(network())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "voice-user-transfer:$sessionId", ExistingWorkPolicy.KEEP, request,
        )
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun schedule(context: Context, sessionId: String, delaySeconds: Long, append: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SESSION_ID to sessionId))
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DeliveryPolicy.workName(sessionId),
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.foreground(applicationContext, inputData.getString(SyncScheduler.SESSION_ID).orEmpty())

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(SyncScheduler.SESSION_ID) ?: run {
            SyncScheduler.enqueue(applicationContext)
            return Result.success()
        }
        return try {
            if (inputData.getBoolean("interactive_transfer", false)) setForeground(getForegroundInfo())
            val delay = TransferExecution.run(applicationContext, sessionId, inputData.getBoolean("interactive_transfer", false))
            if (delay != null) SyncScheduler.next(applicationContext, sessionId, delay)
            Result.success()
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            Result.retry()
        }
    }
}

class SyncWatchdog(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AppGraph.store(applicationContext).deleteAllVerifiedAudio()
        SyncScheduler.enqueue(applicationContext)
        return Result.success()
    }
}
