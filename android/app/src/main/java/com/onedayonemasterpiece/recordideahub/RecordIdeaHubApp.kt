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
        WavChunkWriter.recoverPartials(audioDirectory).forEach { recovered ->
            if (store.session(recovered.sessionId) != null) {
                store.addChunk(
                    sessionId = recovered.sessionId,
                    chunkIndex = recovered.chunkIndex,
                    startMs = recovered.startMs,
                    endMs = recovered.endMs,
                    path = recovered.file.absolutePath,
                    sha256 = recovered.sha256,
                )
            } else {
                recovered.file.delete()
            }
        }
        SyncScheduler.enqueue(this)
    }
}
