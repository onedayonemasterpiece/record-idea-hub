package com.onedayonemasterpiece.recordideahub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.time.OffsetDateTime
import kotlin.math.sqrt

class RecordingService : Service() {
    @Volatile private var captureRequested = false
    @Volatile private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val store by lazy { AppGraph.store(this) }
    private val audioDirectory by lazy { File(filesDir, "audio") }
    private var sessionId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val runtime by lazy { RecordingRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNewSession()
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_FINISH -> finishSession()
        }
        return START_NOT_STICKY
    }

    private fun startNewSession() {
        if (store.activeSession() != null) return
        val id = newSessionId()
        sessionId = id
        store.createSession(
            sessionId = id,
            startedAt = OffsetDateTime.now().toString(),
            timezone = currentTimezone(),
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
        enterForeground("Запись идёт", paused = false)
        beginCapture()
        broadcast()
    }

    private fun pauseSession() {
        val active = store.activeSession() ?: return
        sessionId = active.sessionId
        stopCapture()
        store.setCaptureState(active.sessionId, CaptureState.PAUSED)
        runtime.update(active.sessionId, store.session(active.sessionId)?.durationMs ?: active.durationMs)
        enterForeground("Запись на паузе", paused = true)
        SyncScheduler.enqueue(this)
        broadcast()
    }

    private fun resumeSession() {
        val active = store.activeSession() ?: return
        sessionId = active.sessionId
        store.setCaptureState(active.sessionId, CaptureState.RECORDING)
        enterForeground("Запись идёт", paused = false)
        beginCapture()
        broadcast()
    }

    private fun finishSession() {
        val active = store.activeSession() ?: return
        sessionId = active.sessionId
        stopCapture()
        val refreshed = store.session(active.sessionId) ?: return
        if (refreshed.durationMs < MIN_SESSION_MS || refreshed.chunkCount == 0) {
            store.discardSession(active.sessionId)
            runtime.clear(active.sessionId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            broadcast("Слишком короткая запись удалена")
            return
        }
        store.finishSession(active.sessionId, OffsetDateTime.now().toString())
        runtime.clear(active.sessionId)
        SyncScheduler.enqueue(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcast()
    }

    @Synchronized
    private fun beginCapture() {
        if (captureThread?.isAlive == true) return
        val id = sessionId ?: store.activeSession()?.sessionId ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pauseForMicrophoneFailure(id, "Разрешение на микрофон отозвано")
            return
        }
        captureRequested = true
        acquireWakeLock()
        captureThread = Thread({ captureLoop(id) }, "record-idea-hub-capture").also { it.start() }
    }

    private fun captureLoop(id: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pauseForMicrophoneFailure(id, "Нет разрешения на запись звука")
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            WavChunkWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            pauseForMicrophoneFailure(id, "Устройство не предоставило аудиобуфер")
            return
        }
        val bufferSize = maxOf(minBuffer * 2, 8192)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(WavChunkWriter.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (exc: SecurityException) {
            pauseForMicrophoneFailure(id, "Android запретил доступ к микрофону")
            return
        } catch (exc: RuntimeException) {
            pauseForMicrophoneFailure(id, "Не удалось открыть микрофон: ${exc.message}")
            return
        }
        audioRecord = recorder
        val buffer = ByteArray(bufferSize)
        var writer = newWriter(id)
        var silentMs = 0L
        var lastRuntimeUpdate = 0L
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO permission was revoked")
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
            while (captureRequested) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw IllegalStateException("Android audio service was restarted")
                }
                if (count < 0) {
                    throw IllegalStateException("AudioRecord read failed: $count")
                }
                if (count == 0) continue
                writer.write(buffer, count)
                val liveDuration = writer.startMs + writer.durationMs
                if (liveDuration - lastRuntimeUpdate >= 500L) {
                    runtime.update(id, liveDuration)
                    lastRuntimeUpdate = liveDuration
                }
                val blockMs = count.toLong() * 1000L / (WavChunkWriter.SAMPLE_RATE * 2L)
                silentMs = if (rms(buffer, count) < SILENCE_RMS) silentMs + blockMs else 0L
                val shouldRoll = writer.durationMs >= HARD_CHUNK_MS ||
                    (writer.durationMs >= MIN_CHUNK_MS && silentMs >= SILENCE_WINDOW_MS)
                if (shouldRoll) {
                    persist(writer.close())
                    writer = newWriter(id)
                    silentMs = 0L
                }
            }
        } catch (exc: SecurityException) {
            if (captureRequested) {
                captureRequested = false
                store.setCaptureState(id, CaptureState.PAUSED)
                store.setLocalError(id, "Доступ к микрофону отозван; сохранён записанный фрагмент")
                enterForeground("Нужно разрешение на микрофон", paused = true)
            }
        } catch (exc: Exception) {
            if (captureRequested) {
                captureRequested = false
                store.setCaptureState(id, CaptureState.PAUSED)
                store.setLocalError(id, "Ошибка записи: ${exc.message}")
                enterForeground("Запись остановлена с ошибкой", paused = true)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            audioRecord = null
            persist(writer.close())
            releaseWakeLock()
            broadcast()
        }
    }

    private fun pauseForMicrophoneFailure(id: String, message: String) {
        captureRequested = false
        store.setCaptureState(id, CaptureState.PAUSED)
        store.setLocalError(id, message)
        enterForeground("Микрофон недоступен", paused = true)
        releaseWakeLock()
        broadcast(message)
    }

    private fun newWriter(id: String): WavChunkWriter {
        val startMs = store.session(id)?.durationMs ?: 0L
        return WavChunkWriter(
            directory = audioDirectory,
            sessionId = id,
            chunkIndex = store.nextChunkIndex(id),
            startMs = startMs,
        )
    }

    private fun persist(chunk: WavChunkWriter.ClosedChunk?) {
        if (chunk == null) return
        store.addChunk(
            sessionId = chunk.sessionId,
            chunkIndex = chunk.chunkIndex,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            path = chunk.file.absolutePath,
            sha256 = chunk.sha256,
        )
        SyncScheduler.enqueue(this)
    }

    @Synchronized
    private fun stopCapture() {
        captureRequested = false
        runCatching { audioRecord?.stop() }
        captureThread?.join(5_000)
        captureThread = null
        releaseWakeLock()
    }

    private fun rms(buffer: ByteArray, count: Int): Double {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < count) {
            val sample = (
                (buffer[index + 1].toInt() shl 8) or
                    (buffer[index].toInt() and 0xff)
                ).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            samples++
            index += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun enterForeground(text: String, paused: Boolean) {
        val notification = notification(text, paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(text: String, paused: Boolean): Notification {
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (paused) "Продолжить" else "Пауза"
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.onedayonemasterpiece.recordideahub.R.drawable.ic_mic)
            .setContentTitle("Record Idea Hub")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, toggleLabel, serviceIntent(toggleAction, 1))
            .addAction(0, "Завершить", serviceIntent(ACTION_FINISH, 2))
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:voice-capture",
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(com.onedayonemasterpiece.recordideahub.R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun broadcast(message: String? = null) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    override fun onDestroy() {
        if (captureRequested) {
            stopCapture()
            sessionId?.let { store.setCaptureState(it, CaptureState.PAUSED) }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.onedayonemasterpiece.recordideahub.START"
        const val ACTION_PAUSE = "com.onedayonemasterpiece.recordideahub.PAUSE"
        const val ACTION_RESUME = "com.onedayonemasterpiece.recordideahub.RESUME"
        const val ACTION_FINISH = "com.onedayonemasterpiece.recordideahub.FINISH"
        const val ACTION_STATE_CHANGED = "com.onedayonemasterpiece.recordideahub.STATE_CHANGED"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "record-idea-hub-recording"
        private const val NOTIFICATION_ID = 7001
        private const val MIN_SESSION_MS = 5_000L
        private const val MIN_CHUNK_MS = 75_000L
        private const val HARD_CHUNK_MS = 120_000L
        private const val SILENCE_WINDOW_MS = 600L
        private const val SILENCE_RMS = 450.0
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun command(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_RESUME) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
