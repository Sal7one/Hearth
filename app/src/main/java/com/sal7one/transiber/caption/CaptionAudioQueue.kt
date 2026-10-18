package com.sal7one.transiber.caption

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/**
 * Non-blocking capture ingress for every legacy/local/cloud SttEngine.
 * The sample budget includes the frame being processed, independent of recorder read size.
 * Overload fails this timeline instead of silently deleting already accepted speech.
 */
internal class CaptionAudioQueue(private val maxQueuedSamples: Int = 16_000) {
    init { require(maxQueuedSamples in 1..48_000) }
    private val lock = Any()
    private var pending = 0
    private var closed = false
    private var consuming = false
    private var error: Throwable? = null
    private val frames = Channel<ShortArray>(Channel.UNLIMITED, onUndeliveredElement = ::completed)

    val failure: Throwable? get() = synchronized(lock) { error }
    val pendingSamples: Int get() = synchronized(lock) { pending }

    /** Copy only accepted samples: the recorder may reuse its array as soon as this returns. */
    fun tryAccept(samples: ShortArray, sampleCount: Int): Boolean = synchronized(lock) {
        if (closed) return false
        if (sampleCount !in 1..samples.size || sampleCount > 16_000) {
            fail(IllegalArgumentException("Caption audio frame must contain 1..16000 samples"))
            return false
        }
        if (sampleCount > maxQueuedSamples - pending) {
            fail(IllegalStateException("Audio processing cannot keep up: $pending samples pending; queue limit $maxQueuedSamples"))
            return false
        }
        val copy = samples.copyOf(sampleCount)
        pending += sampleCount
        check(frames.trySend(copy).isSuccess)
        true
    }

    /** One ordered inference lane; errors remain errors and retire all queued input. */
    suspend fun consume(process: suspend (ShortArray) -> Unit) {
        synchronized(lock) { check(!consuming) { "Caption audio already has a consumer" }; consuming = true }
        try {
            for (samples in frames) {
                try { process(samples) } finally { completed(samples) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            synchronized(lock) { if (error == null) error = cause }
            throw cause
        } finally { cancel() }
    }

    /** Source ended normally: deliver every accepted frame before finalizing the recognizer. */
    fun finish() = synchronized(lock) { closed = true; frames.close(); Unit }

    /** Clear/Stop/new generation: discard waiting input; the caller joins in-flight inference. */
    fun cancel() = synchronized(lock) { closed = true; frames.cancel() }

    private fun completed(samples: ShortArray) = synchronized(lock) { pending -= samples.size }
    private fun fail(cause: Throwable) {
        error = cause
        closed = true
        frames.cancel()
    }
}
