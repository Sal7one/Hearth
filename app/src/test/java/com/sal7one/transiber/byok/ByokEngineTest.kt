package com.sal7one.transiber.byok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavEncoderTest {

    @Test
    fun headerIsCanonicalLittleEndianPcm16() {
        val wav = WavEncoder.encodePcm16(shortArrayOf(0, 1, -1, Short.MAX_VALUE), sampleRate = 16_000)
        // RIFF container
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        // fmt chunk: PCM(1), mono, rate, byteRate, align, 16-bit
        fun le32(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8) or
            ((wav[o + 2].toInt() and 0xFF) shl 16) or ((wav[o + 3].toInt() and 0xFF) shl 24)
        fun le16(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8)
        assertEquals(1, le16(20))
        assertEquals(1, le16(22))
        assertEquals(16_000, le32(24))
        assertEquals(32_000, le32(28))
        assertEquals(2, le16(32))
        assertEquals(16, le16(34))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(8, le32(40))
        assertEquals(44 + 8, wav.size)
        // Samples land little-endian signed.
        assertEquals((-1).toShort().toInt(), le16(48).toShort().toInt())
    }
}

class UtteranceBufferTest {

    private val rate = 16_000

    private fun chunk(ms: Int, level: Short = 8_000) =
        ShortArray(ms * rate / 1000) { level }

    @Test
    fun silenceBeforeAnyVoiceIsDropped() {
        val buffer = UtteranceBuffer(rate, maxMs = 12_000, closeSilenceMs = 650)
        assertNull(buffer.push(chunk(2_000), rate, active = false))
        assertNull(buffer.flush()) // nothing was ever voiced
    }

    @Test
    fun utteranceClosesAfterSilenceFollowsVoice() {
        val buffer = UtteranceBuffer(rate, maxMs = 12_000, closeSilenceMs = 650)
        assertNull(buffer.push(chunk(500), rate, active = true))
        assertNull(buffer.push(chunk(500), rate, active = false)) // 500ms < 650
        val done = buffer.push(chunk(200), rate, active = false) // 700ms ≥ 650
        assertNotNull(done)
        // The closing silence chunk is part of the buffer: 500+500+200ms
        assertEquals((1_200 * rate / 1000), done!!.size)
        assertNull(buffer.flush()) // drained
    }

    @Test
    fun continuousVoiceHitsTheLengthCap() {
        val buffer = UtteranceBuffer(rate, maxMs = 2_000, closeSilenceMs = 650)
        var out: ShortArray? = null
        for (i in 0 until 10) {
            out = buffer.push(chunk(500), rate, active = true)
            if (out != null) break
        }
        assertNotNull(out)
        // Cap engages at ≥ 2000ms of speech: the chunk that crossed the line
        // is included, the next utterance starts clean.
        assertTrue(out!!.size >= 2_000 * rate / 1000)
        assertNull(buffer.push(chunk(100), rate, active = true)) // fresh, short
    }

    @Test
    fun resamplingChunksDropsIntoTheBufferRate() {
        val buffer = UtteranceBuffer(rate, maxMs = 12_000, closeSilenceMs = 10_000)
        buffer.push(ShortArray(48_000) { 8_000 }, chunkSampleRate = 48_000, active = true)
        val done = buffer.push(chunk(10), rate, active = false) // silence past cap? no: closeSilence huge
        assertNull(done) // not closed yet — just verifying no crash/resample math
    }
}
