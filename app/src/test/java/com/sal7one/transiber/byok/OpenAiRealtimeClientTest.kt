package com.sal7one.transiber.byok

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The realtime API only accepts 24 kHz PCM while the capture pipeline is
 * 16 kHz — the resampler is the one piece of the wire client that is pure
 * logic, so it gets pinned by tests.
 */
class OpenAiRealtimeClientTest {

    private val client = OpenAiRealtimeClient(apiKey = "test-key-does-not-connect")

    @Test
    fun `resamples 16k to 24k at the 3-2 ratio`() {
        val input = shortArrayOf(0, 1000, 2000, 3000)
        val out = client.resample16kTo24k(input)
        assertEquals(input.size * 3 / 2, out.size)
    }

    @Test
    fun `linear interpolation lands on grid points`() {
        val input = shortArrayOf(0, 1000, 2000, 3000)
        val out = client.resample16kTo24k(input)
        // pos = i*2/3: 0, 0.667, 1.333, 2.0, 2.667, 3.333→clamped to last;
        // Double.toInt truncates toward zero (666, 2666 — not rounded up).
        val expected = intArrayOf(0, 666, 1333, 2000, 2666, 3000)
        for (i in expected.indices) {
            assertEquals(
                "sample $i",
                expected[i],
                out[i].toInt(),
            )
        }
    }

    @Test
    fun `single-sample input is padded without crashing`() {
        val out = client.resample16kTo24k(shortArrayOf(42))
        assertEquals(1, out.size)
        assertEquals(42, out[0].toInt())
    }

    @Test
    fun `silence stays silence`() {
        val out = client.resample16kTo24k(ShortArray(1600))
        assertEquals(2400, out.size)
        assertEquals(0, out.sum().toInt())
    }
}
