package com.sal7one.common_jni

import com.sal7one.common_jni.audio.Pcm16Resampler
import org.junit.Assert.*
import org.junit.Test

class Pcm16ResamplerTest {
    @Test fun arbitrarilySplitCaptureMatchesContinuousAudio() {
        val input = ShortArray(3201) { ((it * 503) % 65536 - 32768).toShort() }
        val whole = Pcm16Resampler(16000, 24000).push(input)
        for (size in listOf(1, 3, 799, 800, 1600)) {
            val streaming = Pcm16Resampler(16000, 24000)
            val output = input.toList().chunked(size).flatMap { streaming.push(it.toShortArray()).toList() }.toShortArray()
            assertArrayEquals("split=$size", whole, output)
        }
    }
    @Test fun repeatedSilenceNeverChangesAmplitudeOrLosesSampleClock() {
        val r = Pcm16Resampler(16000, 24000)
        val output = (1..20).flatMap { r.push(ShortArray(800)).toList() }
        assertEquals(23999, output.size)
        assertTrue(output.all { it == 0.toShort() })
    }
    @Test fun signedEndpointsAndDownsamplingStayCorrect() {
        val r = Pcm16Resampler(48000, 16000)
        assertArrayEquals(shortArrayOf(-32768, 32767), r.push(shortArrayOf(-32768, 0, 0, 32767)))
    }
    @Test fun invalidRatesFailBeforeDivision() {
        assertThrows(IllegalArgumentException::class.java) { Pcm16Resampler(0, 24000) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16Resampler.frame(shortArrayOf(1), 16000, 0) }
    }
}
