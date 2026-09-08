package com.sal7one.common_jni.audio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Single-consumer queue for reusable resources.
 *
 * A rejected, overflow-dropped, cancelled, or explicitly discarded element is
 * returned to its owner exactly through [release]. The element itself should
 * still make release idempotent because the consumer owns successful receives.
 */
internal class ReleasingPooledChannel<T : Any>(
    capacity: Int,
    private val release: (T) -> Unit,
) {
    private val channel = Channel<T>(
        capacity = capacity,
        onUndeliveredElement = release,
    )

    val flow: Flow<T> = channel.receiveAsFlow()

    fun offer(element: T) {
        // A bounded SUSPEND channel plus trySend gives explicit DROP_LATEST
        // behavior: a full or closed channel rejects the new element and we
        // release it here. Channel DROP_LATEST reports success and does not
        // invoke onUndeliveredElement for the dropped item.
        if (channel.trySend(element).isFailure) release(element)
    }

    fun discardPending() {
        while (true) release(channel.tryReceive().getOrNull() ?: return)
    }

    fun cancel() {
        channel.cancel()
    }
}
