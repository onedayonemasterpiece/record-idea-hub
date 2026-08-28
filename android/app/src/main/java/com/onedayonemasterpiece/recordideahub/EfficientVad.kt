package com.onedayonemasterpiece.recordideahub

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.Closeable
import kotlin.math.sqrt

class EfficientVad(private val enabled: Boolean) : Closeable {
    private var detector: VadWebRTC? = if (enabled) {
        runCatching {
            VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_480,
                mode = Mode.LOW_BITRATE,
                speechDurationMs = 0,
                silenceDurationMs = 0,
            )
        }.getOrNull()
    } else {
        null
    }
    private var failedOpen = enabled && detector == null
    private var noiseFloorRms = 80.0

    val isFailOpen: Boolean
        get() = failedOpen

    fun isSpeech(frame: ShortArray): Boolean {
        if (!enabled || failedOpen) return true
        val activeDetector = detector ?: return true
        val rms = rms(frame)

        // Skip JNI only for near-digital silence. WebRTC VAD is deliberately still called for
        // quiet acoustic frames so an energy threshold cannot clip soft speech beginnings.
        if (rms <= DIGITAL_SILENCE_RMS) {
            updateNoiseFloor(rms)
            return false
        }
        return try {
            activeDetector.isSpeech(frame).also { speech ->
                if (!speech) updateNoiseFloor(rms)
            }
        } catch (_: Throwable) {
            failedOpen = true
            runCatching { activeDetector.close() }
            detector = null
            true
        }
    }

    private fun updateNoiseFloor(value: Double) {
        val bounded = value.coerceAtMost(noiseFloorRms * 3.0 + 200.0)
        noiseFloorRms = noiseFloorRms * 0.995 + bounded * 0.005
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
        const val ENGINE_NAME = "webrtc_vad"
        const val ENGINE_VERSION = "2.0.10-cf.4"
        const val CONFIG_VERSION = "vad-auto-pause-efficient-v1"
        const val MODE = 1
        const val FRAME_SAMPLES = 480
        const val FRAME_MS = 30L
        private const val DIGITAL_SILENCE_RMS = 20.0
    }
}
