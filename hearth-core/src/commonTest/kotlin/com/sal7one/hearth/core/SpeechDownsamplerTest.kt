package com.sal7one.hearth.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechDownsamplerTest {
    @Test fun arbitraryReadBoundariesAndTailPreserveSamples() {
        for (rate in listOf(44_100, 48_000)) {
            val input = ShortArray(rate + 19) { (it * 65521).toShort() }
            val full = SpeechDownsampler.forRate(rate)!!
            val expected = full.push(input) + full.finish()
            val chunked = SpeechDownsampler.forRate(rate)!!
            val actual = ShortArray(expected.size)
            var written = 0
            var offset = 0
            while (offset < input.size) {
                val end = minOf(offset + offset % 97 + 1, input.size)
                val output = chunked.push(input.copyOfRange(offset, end))
                output.copyInto(actual, written)
                written += output.size
                offset = end
            }
            val tail = chunked.finish()
            tail.copyInto(actual, written)
            assertEquals(input.size * 16_000 / rate, written + tail.size)
            assertContentEquals(expected, actual)
            assertTrue(chunked.finish().isEmpty())
        }
    }

    @Test fun attenuatesOutOfBandTonesAndKeepsSpeechBandAndDc() {
        for (rate in listOf(44_100, 48_000)) {
            fun rms(frequency: Int): Double {
                val input = ShortArray(rate / 4) { (12000 * sin(2 * PI * frequency * it / rate)).roundToInt().toShort() }
                val filter = SpeechDownsampler.forRate(rate)!!
                val output = filter.push(input) + filter.finish()
                val middle = 100 until output.size - 100
                return sqrt(middle.sumOf { output[it].toDouble() * output[it] } / middle.count())
            }
            assertTrue(rms(10_000) < 2.0, "$rate Hz input should reject a 10 kHz tone")
            assertTrue(rms(6_500) in 8_300.0..8_700.0, "$rate Hz input should preserve 6.5 kHz")
            val filter = SpeechDownsampler.forRate(rate)!!
            val input = ShortArray(113) { Short.MIN_VALUE }
            assertContentEquals(ShortArray(input.size * 16_000 / rate) { Short.MIN_VALUE }, filter.push(input) + filter.finish())
        }
    }
}
