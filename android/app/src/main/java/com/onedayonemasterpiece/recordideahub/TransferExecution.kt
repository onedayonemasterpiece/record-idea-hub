package com.onedayonemasterpiece.recordideahub

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object TransferExecution {
    private val executor = Executors.newFixedThreadPool(2)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    suspend fun run(context: Context, sessionId: String, userInitiated: Boolean = false, onProgress: () -> Unit = {}): Long? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = TransferCancellation()
            val future = executor.submit {
                val lock = locks.computeIfAbsent(sessionId) { ReentrantLock() }
                try {
                    cancellation.check()
                    val delay = if (!lock.tryLock()) 15L else try {
                        SyncEngine(context.applicationContext, cancellation, onProgress).run(sessionId, userInitiated)
                    } finally { lock.unlock() }
                    if (continuation.isActive) continuation.resume(delay)
                } catch (exc: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(exc)
                }
            }
            continuation.invokeOnCancellation {
                cancellation.cancel()
                future.cancel(true)
            }
        }
}
