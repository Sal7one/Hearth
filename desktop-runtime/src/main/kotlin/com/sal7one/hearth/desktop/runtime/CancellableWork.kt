package com.sal7one.hearth.desktop.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation signals native/transport work immediately, and joins its owner before returning. */
internal suspend fun <T> joinedBlocking(cancel: () -> Unit, block: () -> T): T = coroutineScope {
    val done = CompletableDeferred<Unit>()
    try {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching(cancel) }
            try {
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    try {
                        val value = block()
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { done.complete(Unit) }
                }
            } catch (error: Throwable) {
                done.complete(Unit)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    } finally { withContext(NonCancellable) { done.await() } }
}
