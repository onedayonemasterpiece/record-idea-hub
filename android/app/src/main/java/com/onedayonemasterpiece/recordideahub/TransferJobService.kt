package com.onedayonemasterpiece.recordideahub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.work.ForegroundInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

internal object TransferNotifications {
    private const val CHANNEL = "voice-delivery"
    fun id(sessionId: String): Int = 0x40000000 or (sessionId.hashCode() and 0x0fffffff)

    fun build(context: Context, sessionId: String): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Передача записей", NotificationManager.IMPORTANCE_LOW),
        )
        val session = AppGraph.store(context).session(sessionId)
        val uploaded = session?.chunksUploaded ?: 0
        val total = session?.chunkCount ?: 0
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Record Idea Hub · передача")
            .setContentText("Аудио сохранено на телефоне · $uploaded/$total сегментов")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), uploaded, total == 0).build()
    }

    fun foreground(context: Context, sessionId: String): ForegroundInfo = ForegroundInfo(
        id(sessionId), build(context, sessionId), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}

@RequiresApi(34)
class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val sessionId = params.extras.getString(SyncScheduler.SESSION_ID) ?: return false
        fun notifyProgress() {
            setNotification(params, TransferNotifications.id(sessionId),
                TransferNotifications.build(this, sessionId), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        notifyProgress()
        jobs[params.jobId] = scope.launch {
            try {
                val delay = TransferExecution.run(applicationContext, sessionId, userInitiated = true) {
                    handler.post { if (jobs.containsKey(params.jobId)) notifyProgress() }
                }
                if (delay != null) SyncScheduler.next(applicationContext, sessionId, delay)
                jobFinished(params, false)
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                SyncScheduler.next(applicationContext, sessionId, 30L)
                jobFinished(params, false)
            } finally { jobs.remove(params.jobId) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        params.extras.getString(SyncScheduler.SESSION_ID)?.let {
            // The platform can stop a transfer; only durable delivery is resumed.
            SyncScheduler.next(applicationContext, it, 30L)
            android.util.Log.i("VoiceTransfer", "job stopped reason=${params.stopReason}")
        }
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun schedule(context: Context, sessionId: String): Boolean {
            if (Build.VERSION.SDK_INT < 34) return false
            return try {
                val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace("voice-transfer")
                val existing = scheduler.allPendingJobs
                val pending = existing.firstOrNull { it.extras.getString(SyncScheduler.SESSION_ID) == sessionId }
                if (pending != null) return true
                var id = TransferNotifications.id(sessionId)
                while (existing.any { it.id == id }) id++
                val bytes = AppGraph.store(context).chunks(sessionId).filter { !it.uploaded }
                    .sumOf { File(it.path).length() }
                val job = JobInfo.Builder(id, ComponentName(context, TransferJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(64 * 1024L, bytes.coerceAtLeast(1L))
                    .setExtras(PersistableBundle().apply { putString(SyncScheduler.SESSION_ID, sessionId) })
                    .build()
                scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            } catch (exc: IllegalStateException) {
                false
            } catch (exc: SecurityException) {
                false
            }
        }
    }
}
