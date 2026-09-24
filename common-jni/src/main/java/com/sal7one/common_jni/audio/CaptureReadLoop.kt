package com.sal7one.common_jni.audio

import kotlinx.coroutines.delay

/** Sample-count clock: chunk timestamps cannot drift through per-chunk rounding. */
internal class CaptureSampleClock(private val sampleRate: Int) {
    init { require(sampleRate > 0) }

    private var nextSample = 0L

    fun advance(samples: Int): Pair<Long, Long> {
        require(samples >= 0)
        val start = nextSample * 1000L / sampleRate
        nextSample += samples
        val end = nextSample * 1000L / sampleRate
        return start to (end - start)
    }
}

/**
 * Android-independent short-array read loop. Production supplies AudioRecord.read;
 * host tests supply a fake reader so a negative read must terminate exactly once.
 * Returns the raw AudioRecord error code, or null for a normal stop.
 */
internal suspend fun captureShortChunks(
    sampleRate: Int,
    chunkSamples: Int,
    active: () -> Boolean,
    read: (ShortArray) -> Int,
    publish: suspend (AudioChunkData) -> Unit,
    onDelivered: () -> Unit,
): Int? {
    val buffer = ShortArray(chunkSamples)
    val clock = CaptureSampleClock(sampleRate)
    while (active()) {
        val count = read(buffer)
        if (!active()) return null
        when {
            count < 0 -> return count
            count == 0 -> delay(5)
            count > buffer.size -> return -1 // Reader violated the supplied buffer bound.
            else -> {
                val (timestampMs, durationMs) = clock.advance(count)
                onDelivered()
                publish(AudioChunkData(
                    samples = buffer.copyOf(count),
                    sampleRate = sampleRate,
                    timestampMs = timestampMs,
                    durationMs = durationMs,
                ))
            }
        }
    }
    return null
}
