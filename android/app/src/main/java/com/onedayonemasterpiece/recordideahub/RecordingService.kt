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
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.ArrayDeque

class RecordingService : Service() {
    @Volatile private var captureRequested = false
    @Volatile private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val store by lazy { AppGraph.store(this) }
    private val config by lazy { AppGraph.config(this) }
    private val audioDirectory by lazy { File(filesDir, "audio") }
    private var sessionId: String? = null
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
        val autoSilence = config.autoSilenceEnabled
        store.createSession(
            sessionId = id,
            startedAt = OffsetDateTime.now().toString(),
            timezone = currentTimezone(),
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            protocolVersion = 2,
            capturePolicy = if (autoSilence) {
                CapturePolicy.VOICE_ACTIVITY_AUTO_PAUSE_V1
            } else {
                CapturePolicy.CONTINUOUS_V1
            },
            vadEngine = if (autoSilence) "webrtc_vad" else null,
        )
        enterForeground("Слушаю · тишина не записывается", paused = false)
        beginCapture()
        broadcast()
    }

    private fun pauseSession() {
        val active = store.activeSession() ?: return
        sessionId = active.sessionId
        if (active.captureState == CaptureState.RECORDING) stopCapture()
        store.beginManualPause(active.sessionId)
        val refreshed = store.session(active.sessionId) ?: active
        runtime.update(
            active.sessionId,
            refreshed.durationMs,
            elapsedFromStart(refreshed.startedAt),
            refreshed.autoSilenceSkippedMs,
            CaptureActivity.MANUAL_PAUSE,
        )
        enterForeground("Пауза · микрофон остановлен", paused = true)
        SyncScheduler.enqueue(this)
        broadcast()
    }

    private fun resumeSession() {
        val active = store.activeSession() ?: return
        sessionId = active.sessionId
        store.endManualPause(active.sessionId)
        enterForeground("Слушаю · тишина не записывается", paused = false)
        beginCapture()
        broadcast()
    }

    private fun finishSession() {
        val active = store.activeSession() ?: return
        sessionId = active.sessionId
        if (active.captureState == CaptureState.RECORDING) {
            stopCapture()
        } else {
            store.endManualPause(active.sessionId)
        }
        val refreshed = store.session(active.sessionId) ?: return
        if (refreshed.durationMs < MIN_SESSION_MS || refreshed.chunkCount == 0) {
            store.discardSession(active.sessionId)
            runtime.clear(active.sessionId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            broadcast("Слишком короткая запись удалена")
            return
        }
        val endedAt = OffsetDateTime.now()
        val wallElapsed = elapsedBetween(refreshed.startedAt, endedAt)
        val autoSkipped = (
            wallElapsed - refreshed.manualPauseMs - refreshed.durationMs
            ).coerceAtLeast(0L)
        store.finishSession(
            sessionId = active.sessionId,
            endedAt = endedAt.toString(),
            wallElapsedMs = wallElapsed,
            autoSilenceSkippedMs = autoSkipped,
        )
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
        store.setCaptureState(id, CaptureState.RECORDING, CaptureActivity.AUTO_SILENCE)
        captureRequested = true
        captureThread = Thread({ captureLoop(id) }, "record-idea-hub-capture").also { it.start() }
    }

    private fun captureLoop(id: String) {
        val initial = store.session(id) ?: return
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioProfile.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            pauseForMicrophoneFailure(id, "Устройство не предоставило аудиобуфер")
            return
        }
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioProfile.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, EfficientVad.FRAME_SAMPLES * 8))
                .build()
        } catch (exc: Exception) {
            pauseForMicrophoneFailure(id, "Не удалось открыть микрофон: ${exc.message}")
            return
        }
        audioRecord = recorder
        val suppressor = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(recorder.audioSessionId) }.getOrNull()
        } else {
            null
        }
        val detector = EfficientVad(initial.capturePolicy == CapturePolicy.VOICE_ACTIVITY_AUTO_PAUSE_V1)
        val latch = SpeechLatch(attackFrames = 3, hangoverFrames = 60)
        val preRoll = ArrayDeque<FramePacket>()
        var writer: M4aChunkWriter? = null
        var persistedAudioMs = initial.durationMs
        var activity = if (initial.capturePolicy == CapturePolicy.CONTINUOUS_V1) {
            CaptureActivity.VOICE
        } else {
            CaptureActivity.AUTO_SILENCE
        }
        var lastBroadcastActivity: String? = null
        var lastStoreUpdateWallMs = -1L
        var silenceStartedWallMs: Long? = null
        val frame = ShortArray(EfficientVad.FRAME_SAMPLES)
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter recording state"
            }
            while (captureRequested) {
                if (!readFrame(recorder, frame)) continue
                val wallEndMs = elapsedFromStart(initial.startedAt)
                val wallStartMs = (wallEndMs - EfficientVad.FRAME_MS).coerceAtLeast(0L)
                val packet = FramePacket(frame.copyOf(), wallStartMs, wallEndMs)
                val wasActive = latch.active
                val active = latch.onFrame(detector.isSpeech(packet.samples))
                if (active) {
                    silenceStartedWallMs = null
                    if (!wasActive) {
                        ensurePreRoll(preRoll, packet)
                        writer = writer ?: newWriter(id, persistedAudioMs)
                        while (preRoll.isNotEmpty()) {
                            val buffered = preRoll.removeFirst()
                            writer?.writeFrame(buffered.samples, buffered.wallStartMs, buffered.wallEndMs)
                        }
                    } else {
                        writer = writer ?: newWriter(id, persistedAudioMs)
                        writer?.writeFrame(packet.samples, packet.wallStartMs, packet.wallEndMs)
                    }
                    activity = if (detector.isFailOpen) {
                        CaptureActivity.FALLBACK_CONTINUOUS
                    } else {
                        CaptureActivity.VOICE
                    }
                    if ((writer?.durationMs ?: 0L) >= M4aChunkWriter.TARGET_SEGMENT_MS) {
                        persistedAudioMs = persist(writer?.close(), persistedAudioMs)
                        writer = null
                    }
                } else {
                    ensurePreRoll(preRoll, packet)
                    if (silenceStartedWallMs == null) silenceStartedWallMs = wallStartMs
                    activity = CaptureActivity.AUTO_SILENCE
                    val silenceMs = wallEndMs - (silenceStartedWallMs ?: wallEndMs)
                    if (
                        silenceMs >= LONG_SILENCE_CLOSE_MS &&
                        (writer?.durationMs ?: 0L) >= MIN_DURABLE_SEGMENT_MS
                    ) {
                        persistedAudioMs = persist(writer?.close(), persistedAudioMs)
                        writer = null
                    }
                }

                val recordedAudioMs = persistedAudioMs + (writer?.durationMs ?: 0L)
                val currentSession = store.session(id) ?: break
                val autoSkipped = (
                    wallEndMs - currentSession.manualPauseMs - recordedAudioMs
                    ).coerceAtLeast(0L)
                runtime.update(id, recordedAudioMs, wallEndMs, autoSkipped, activity)
                if (
                    lastStoreUpdateWallMs < 0L ||
                    wallEndMs - lastStoreUpdateWallMs >= STORE_UPDATE_INTERVAL_MS ||
                    activity != lastBroadcastActivity
                ) {
                    store.updateCaptureProgress(
                        id,
                        recordedAudioMs,
                        wallEndMs,
                        currentSession.manualPauseMs,
                        autoSkipped,
                        activity,
                    )
                    lastStoreUpdateWallMs = wallEndMs
                }
                if (activity != lastBroadcastActivity) {
                    updateNotification(activity)
                    lastBroadcastActivity = activity
                    broadcast(if (activity == CaptureActivity.FALLBACK_CONTINUOUS) {
                        "Автопропуск недоступен · записываю всё"
                    } else {
                        null
                    })
                }
            }
        } catch (exc: Exception) {
            if (captureRequested) {
                captureRequested = false
                store.beginManualPause(id)
                store.setLocalError(id, "Ошибка записи: ${exc.message}")
                enterForeground("Запись остановлена с ошибкой", paused = true)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            audioRecord = null
            suppressor?.release()
            detector.close()
            persistedAudioMs = persist(writer?.close(), persistedAudioMs)
            val finalSession = store.session(id)
            if (finalSession != null) {
                val wallElapsed = elapsedFromStart(finalSession.startedAt)
                val autoSkipped = (
                    wallElapsed - finalSession.manualPauseMs - persistedAudioMs
                    ).coerceAtLeast(0L)
                store.updateCaptureProgress(
                    id,
                    persistedAudioMs,
                    wallElapsed,
                    finalSession.manualPauseMs,
                    autoSkipped,
                    if (captureRequested) activity else CaptureActivity.IDLE,
                )
                runtime.update(
                    id,
                    persistedAudioMs,
                    wallElapsed,
                    autoSkipped,
                    if (captureRequested) activity else CaptureActivity.IDLE,
                )
            }
            broadcast()
        }
    }

    private fun readFrame(recorder: AudioRecord, target: ShortArray): Boolean {
        var offset = 0
        while (offset < target.size && captureRequested) {
            val count = recorder.read(target, offset, target.size - offset, AudioRecord.READ_BLOCKING)
            if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                throw IllegalStateException("Android audio service was restarted")
            }
            if (count < 0) throw IllegalStateException("AudioRecord read failed: $count")
            if (count == 0) continue
            offset += count
        }
        return offset == target.size
    }

    private fun ensurePreRoll(queue: ArrayDeque<FramePacket>, packet: FramePacket) {
        queue.addLast(packet)
        while (queue.size > PRE_ROLL_FRAMES) queue.removeFirst()
    }

    private fun newWriter(id: String, audioStartMs: Long): M4aChunkWriter = M4aChunkWriter(
        directory = audioDirectory,
        sessionId = id,
        chunkIndex = store.nextChunkIndex(id),
        audioStartMs = audioStartMs,
    )

    private fun persist(chunk: M4aChunkWriter.ClosedChunk?, previousAudioMs: Long): Long {
        if (chunk == null) return previousAudioMs
        store.addChunk(
            sessionId = chunk.sessionId,
            chunkIndex = chunk.chunkIndex,
            startMs = chunk.audioStartMs,
            endMs = chunk.audioEndMs,
            wallStartMs = chunk.wallStartMs,
            wallEndMs = chunk.wallEndMs,
            path = chunk.file.absolutePath,
            sha256 = chunk.sha256,
            mimeType = AudioProfile.MIME_M4A,
        )
        return chunk.audioEndMs
    }

    @Synchronized
    private fun stopCapture() {
        captureRequested = false
        runCatching { audioRecord?.stop() }
        captureThread?.join(10_000)
        captureThread = null
    }

    private fun pauseForMicrophoneFailure(id: String, message: String) {
        captureRequested = false
        store.beginManualPause(id)
        store.setLocalError(id, message)
        enterForeground("Микрофон недоступен", paused = true)
        broadcast(message)
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

    private fun updateNotification(activity: String) {
        val text = when (activity) {
            CaptureActivity.VOICE -> "Записываю голос"
            CaptureActivity.AUTO_SILENCE -> "Слушаю · тишина не записывается"
            CaptureActivity.FALLBACK_CONTINUOUS -> "Автопропуск недоступен · записываю всё"
            else -> "Запись идёт"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, paused = false))
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
            .setSmallIcon(R.drawable.ic_mic)
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

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
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

    private fun elapsedFromStart(startedAt: String): Long = runCatching {
        (System.currentTimeMillis() - OffsetDateTime.parse(startedAt).toInstant().toEpochMilli())
            .coerceAtLeast(0L)
    }.getOrDefault(0L)

    private fun elapsedBetween(startedAt: String, endedAt: OffsetDateTime): Long = runCatching {
        Duration.between(OffsetDateTime.parse(startedAt), endedAt).toMillis().coerceAtLeast(0L)
    }.getOrDefault(0L)

    override fun onDestroy() {
        if (captureRequested) {
            stopCapture()
            sessionId?.let { store.beginManualPause(it) }
        }
        super.onDestroy()
    }

    private data class FramePacket(
        val samples: ShortArray,
        val wallStartMs: Long,
        val wallEndMs: Long,
    )

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
        private const val PRE_ROLL_FRAMES = 30
        private const val STORE_UPDATE_INTERVAL_MS = 2_000L
        private const val LONG_SILENCE_CLOSE_MS = 15_000L
        private const val MIN_DURABLE_SEGMENT_MS = 10_000L

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
