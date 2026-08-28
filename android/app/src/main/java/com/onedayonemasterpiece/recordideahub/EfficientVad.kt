package com.onedayonemasterpiece.recordideahub

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.Closeable
import kotlin.math.max
import kotlin.math.sqrt

class EfficientVad(private val enabled: Boolean) : Closeable {
    private var detector: VadWebRTC? = if (enabled) {
        runCatching {
            VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_320,
                mode = Mode.LOW_BITRATE,
                speechDurationMs = 60,
                silenceDurationMs = 0,
            )
        }.getOrNull()
    } else {
        null
    }
    private var failedOpen = enabled && detector == null
    private var noiseFloorRms = 180.0
    private var frameCounter = 0L

    val engineName: String?
        get() = if (enabled) "webrtc_vad" else null

    val engineVersion: String?
        get() = if (enabled) "2.0.10-cf.4" else null

    val configVersion: String?
        get() = if (enabled) "vad-auto-pause-efficient-v1" else null

    val isFailOpen: Boolean
        get() = failedOpen

    fun isSpeech(frame: ShortArray): Boolean {
        if (!enabled || failedOpen) return true
        val activeDetector = detector ?: return true
        frameCounter++
        val rms = rms(frame)
        val threshold = max(90.0, noiseFloorRms * 1.18)
        val shouldInspect = rms >= threshold || frameCounter % 5L == 0L
        return try {
            val speech = shouldInspect && activeDetector.isSpeech(frame)
            if (!speech) {
                val bounded = rms.coerceAtMost(noiseFloorRms * 3.0 + 200.0)
                noiseFloorRms = noiseFloorRms * 0.995 + bounded * 0.005
            }
            speech
        } catch (_: Throwable) {
            failedOpen = true
            runCatching { activeDetector.close() }
            detector = null
            true
        }
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / frame.size)
    }

    override fun close() {
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    companion object {
        const val FRAME_SAMPLES = 320
        const val FRAME_MS = 20L
    }
}
