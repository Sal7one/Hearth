package com.sal7one.common_jni.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureReadLoopTest {
    @Test
    fun negativeReaderResultStopsAfterOneFailure() = runBlocking {
        for (errorCode in listOf(-1, -2, -3, -6)) {
            var reads = 0
            val delivered = mutableListOf<AudioChunkData>()
            val result = captureShortChunks(
                sampleRate = 16_000,
                chunkSamples = 4,
                active = { true },
                read = { buffer ->
                    reads++
                    if (reads == 1) {
                        buffer.fill(7)
                        4
                    } else errorCode
                },
                publish = delivered::add,
                onDelivered = {},
            )
            assertEquals(errorCode, result)
            assertEquals(2, reads)
            assertEquals(1, delivered.size)
            assertEquals(0L, delivered.single().timestampMs)
        }
    }

    @Test
    fun sampleCounterAvoidsRoundingDriftAndRepresentsSkippedAudio() {
        val clock = CaptureSampleClock(44_100)
        repeat(440) { clock.advance(1024) }
        val (start, duration) = clock.advance(1024)
        assertEquals(440L * 1024 * 1000 / 44_100, start)
        assertEquals(441L * 1024 * 1000 / 44_100 - start, duration)
        val (nextStart, _) = clock.advance(256)
        assertEquals((441L * 1024) * 1000 / 44_100, nextStart)
    }

    @Test
    fun stoppedReaderDoesNotPublishLateChunk() = runBlocking {
        var active = true
        var published = 0
        val result = captureShortChunks(
            sampleRate = 16_000,
            chunkSamples = 4,
            active = { active },
            read = { buffer ->
                buffer.fill(5)
                active = false
                4
            },
            publish = { published++ },
            onDelivered = {},
        )
        assertNull(result)
        assertEquals(0, published)
    }
}
