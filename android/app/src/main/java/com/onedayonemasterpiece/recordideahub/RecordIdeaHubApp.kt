package com.onedayonemasterpiece.recordideahub

import android.app.Application
import java.io.File

class RecordIdeaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = AppGraph.store(this)
        store.markInterruptedRecordingsPaused()
        store.deleteAllVerifiedAudio()
        val audioDirectory = File(filesDir, "audio")

        // Preserve recoverability of unfinished legacy v1 PCM/WAV sessions.
        WavChunkWriter.recoverPartials(audioDirectory).forEach { recovered ->
            val session = store.session(recovered.sessionId)
            if (session != null && session.protocolVersion == 1) {
                store.addChunk(
                    sessionId = recovered.sessionId,
                    chunkIndex = recovered.chunkIndex,
                    startMs = recovered.startMs,
                    endMs = recovered.endMs,
                    wallStartMs = recovered.startMs,
                    wallEndMs = recovered.endMs,
                    path = recovered.file.absolutePath,
                    sha256 = recovered.sha256,
                    mimeType = "audio/wav",
                )
            } else {
                recovered.file.delete()
            }
        }

        // MediaMuxer cannot safely resume an unfinalized MP4 container. Closed segments remain
        // durable; only the currently open .part is discarded and the session stays paused.
        audioDirectory.listFiles { file -> file.name.endsWith(".m4a.part") }
            ?.forEach { partial ->
                val sessionId = partial.name.substringBefore("__")
                partial.delete()
                store.session(sessionId)?.let {
                    store.setLocalError(
                        sessionId,
                        "После аварийного завершения отброшен только незакрытый аудиосегмент",
                    )
                }
            }
        SyncScheduler.initialize(this)
    }
}
