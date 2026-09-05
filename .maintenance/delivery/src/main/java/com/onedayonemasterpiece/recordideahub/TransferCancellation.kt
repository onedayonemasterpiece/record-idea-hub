package com.onedayonemasterpiece.recordideahub

import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

class TransferCancellation {
    @Volatile private var cancelled = false
    private var connection: HttpURLConnection? = null

    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("transfer stopped")
    }

    @Synchronized
    fun attach(value: HttpURLConnection) {
        check()
        connection = value
    }

    @Synchronized
    fun detach(value: HttpURLConnection) {
        if (connection === value) connection = null
    }

    fun cancel() {
        val active = synchronized(this) {
            cancelled = true
            connection.also { connection = null }
        }
        active?.disconnect()
    }
}
