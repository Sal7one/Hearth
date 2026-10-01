package com.sal7one.transiber.caption

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Bounded, sequential voice work. Stop invalidates queued and in-flight requests. */
internal class CaptionSpeechQueue<T>(
    scope: CoroutineScope,
    private val onError: (String) -> Unit,
    private val process: suspend (T) -> Unit,
) : AutoCloseable {
    private data class Entry<T>(val value: T, val generation: Long)
    private val lock = Any()
    private val pending = Channel<Entry<T>>(3)
    private var generation = 0L
    private var closed = false
    private var active: Job? = null
    private val worker = scope.launch {
        for (entry in pending) {
            val operation = launch(start = CoroutineStart.LAZY) {
                if (!isCurrent(entry.generation)) return@launch
                try { process(entry.value) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (isCurrent(entry.generation)) onError(e.message ?: e.toString()) }
            }
            synchronized(lock) {
                if (closed || entry.generation != generation) operation.cancel() else active = operation
            }
            operation.start()
            operation.join()
            synchronized(lock) { if (active === operation) active = null }
        }
    }

    fun offer(value: T): Boolean = synchronized(lock) {
        !closed && pending.trySend(Entry(value, generation)).isSuccess
    }

    private fun isCurrent(value: Long) = synchronized(lock) { !closed && value == generation }

    fun stop() = synchronized(lock) {
        generation++
        while (pending.tryReceive().isSuccess) Unit
        active?.cancel()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            stop()
            pending.close()
        }
        worker.cancel()
    }
}
