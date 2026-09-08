package com.sal7one.common_jni.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Collects one bounded PCM16 prefix, then detaches from the hot audio path. */
internal class OneShotPcm16Probe {
    private companion object {
        // The only production consumer requests two seconds at 16 kHz. Keep a
        // generous fixed ceiling so a future caller cannot turn this one-shot
        // diagnostic copy into an unbounded allocation.
        const val MAX_SAMPLE_COUNT = 1_000_000
    }

    private data class Request(
        val samples: ShortArray,
        var written: Int,
        val result: CompletableDeferred<ShortArray?>,
    )

    private val lock = Any()
    private var request: Request? = null

    fun request(sampleCount: Int): Deferred<ShortArray?> {
        require(sampleCount in 1..MAX_SAMPLE_COUNT) {
            "sampleCount must be in [1,$MAX_SAMPLE_COUNT]"
        }
        val next = Request(ShortArray(sampleCount), 0, CompletableDeferred())
        val previous = synchronized(lock) {
            val old = request
            request = next
            old
        }
        previous?.result?.complete(null)
        return next.result
    }

    fun append(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset) {
        require(offset >= 0 && count >= 0 && offset <= samples.size - count) {
            "PCM16 source range is out of bounds"
        }
        appendFrom(count) { destination, destinationOffset, copyCount ->
            samples.copyInto(
                destination = destination,
                destinationOffset = destinationOffset,
                startIndex = offset,
                endIndex = offset + copyCount,
            )
        }
    }

    fun append(buffer: ByteBuffer, byteOffset: Int, byteCount: Int) {
        require(buffer.isDirect) { "PCM16 probe source must be direct" }
        require(byteOffset >= 0 && byteCount >= 0 && byteCount % Short.SIZE_BYTES == 0 &&
            byteOffset <= buffer.capacity() - byteCount) {
            "PCM16 direct-buffer range is out of bounds"
        }
        appendFrom(byteCount / Short.SIZE_BYTES) { destination, destinationOffset, copyCount ->
            val source = buffer.duplicate().order(ByteOrder.nativeOrder())
            source.position(byteOffset)
            source.limit(byteOffset + byteCount)
            source.slice().order(ByteOrder.nativeOrder()).asShortBuffer().get(
                destination,
                destinationOffset,
                copyCount,
            )
        }
    }

    fun cancel() {
        val pending = synchronized(lock) {
            val old = request
            request = null
            old
        }
        pending?.result?.complete(null)
    }

    private fun appendFrom(
        available: Int,
        copy: (destination: ShortArray, destinationOffset: Int, copyCount: Int) -> Unit,
    ) {
        if (available == 0) return
        var completed: CompletableDeferred<ShortArray?>? = null
        var completedSamples: ShortArray? = null
        synchronized(lock) {
            val pending = request ?: return@synchronized
            if (!pending.result.isActive) {
                request = null
                return@synchronized
            }
            val copyCount = minOf(available, pending.samples.size - pending.written)
            copy(pending.samples, pending.written, copyCount)
            pending.written += copyCount
            if (pending.written == pending.samples.size) {
                request = null
                completed = pending.result
                completedSamples = pending.samples
            }
        }
        completed?.complete(completedSamples)
    }
}
