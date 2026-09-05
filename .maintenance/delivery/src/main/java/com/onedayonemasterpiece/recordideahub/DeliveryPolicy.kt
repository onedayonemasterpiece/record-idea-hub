package com.onedayonemasterpiece.recordideahub

internal object DeliveryPolicy {
    fun workName(sessionId: String): String = "voice-delivery:$sessionId"
    fun mayDeleteAudio(state: String, verified: Boolean, purged: Boolean): Boolean =
        state == RemoteState.PUBLISHED_VERIFIED && verified && purged

    fun maySendComplete(recordingFinishedOnServer: Boolean): Boolean = !recordingFinishedOnServer
}
