package com.sal7one.transiber.benchmark

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BenchmarkAudioTest {
    private fun wav(rate: Int = 16000, channels: Int = 1, frames: Int = rate,
                    sample: (Int, Int) -> Short = { _, _ -> 1234 }): ByteArray {
        val bytes = ByteBuffer.allocate(44 + frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(bytes.capacity() - 8); bytes.put("WAVEfmt ".toByteArray())
        bytes.putInt(16); bytes.putShort(1); bytes.putShort(channels.toShort()); bytes.putInt(rate)
        bytes.putInt(rate * channels * 2); bytes.putShort((channels * 2).toShort()); bytes.putShort(16)
        bytes.put("data".toByteArray()); bytes.putInt(frames * channels * 2)
        repeat(frames) { frame -> repeat(channels) { channel -> bytes.putShort(sample(frame, channel)) } }
        return bytes.array()
    }
    private fun rejected(bytes: ByteArray) {
        try { BenchmarkAudio.decode(bytes); fail("Expected WAV rejection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun stereoDownmixIsOverflowSafeAndMatchesMonoInputHash() {
        val stereo = BenchmarkAudio.decode(wav(channels = 2) { _, channel -> if (channel == 0) 32767 else -32768 })
        val mono = BenchmarkAudio.decode(wav { _, _ -> 0 })
        assertArrayEquals(mono.samples, stereo.samples)
        assertEquals(mono.sha256, stereo.sha256)
        assertEquals(1000L, stereo.durationMs)
        assertEquals(64, stereo.sha256.length)
    }
    @Test fun resamplesExactlyOnceAndKeepsDuration() {
        val decoded = BenchmarkAudio.decode(wav(rate = 48000))
        assertEquals(16000, decoded.samples.size)
        assertTrue(decoded.samples.all { it == 1234.toShort() })
        assertEquals(BenchmarkAudio.decode(wav()).sha256, decoded.sha256)
    }
    @Test fun comparisonGainMatchesHostPolicyAndPreservesSilenceAndNormalLevelAudio() {
        val quiet = shortArrayOf(1, -2, 100, -311)
        val boosted = BenchmarkAudio.normalizeForComparison(quiet)
        assertEquals(Short.MAX_VALUE.toInt() * 0.9, kotlin.math.abs(boosted.minOrNull()!!.toInt()).toDouble(), 2.0)
        assertNotSame(quiet, boosted)
        val silence = shortArrayOf(0, 0, 0)
        assertSame(silence, BenchmarkAudio.normalizeForComparison(silence))
        val normal = shortArrayOf(16384, -22000)
        assertSame(normal, BenchmarkAudio.normalizeForComparison(normal))
    }
    @Test fun rejectsFloatTruncatedOversizedAndPartialFrames() {
        rejected(wav().also { it[20] = 3 })
        rejected(wav().copyOf(50))
        rejected(wav(frames = 16000 * 31))
        rejected(wav(frames = 100))
        rejected(wav().also { it[32] = 4 })
        val partial = wav().copyOf(44 + 16001)
        ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN).putInt(4, partial.size - 8).putInt(40, 16001)
        rejected(partial)
    }
    @Test fun ignoresMetadataChunksButRejectsDuplicateAudio() {
        val base = wav()
        val out = ByteArrayOutputStream()
        out.write(base, 0, 12)
        out.write("JUNK".toByteArray()); out.write(byteArrayOf(2, 0, 0, 0, 7, 9))
        out.write(base, 12, base.size - 12)
        val metadata = out.toByteArray()
        ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN).putInt(4, metadata.size - 8)
        assertEquals(BenchmarkAudio.decode(base).sha256, BenchmarkAudio.decode(metadata).sha256)
        val duplicate = base + base.copyOfRange(36, base.size)
        ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicate.size - 8)
        rejected(duplicate)
    }
    @Test fun boundsInputStreamBeforeUnboundedAllocation() {
        val endless = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(0, off, off + len); return len }
        }
        try { BenchmarkAudio.read(endless); fail("Expected byte cap") } catch (_: IllegalArgumentException) { }
    }
    @Test fun separatesRealtimeFactorFromLoadAndUsesWarmMedian() {
        assertEquals(0.5, BenchmarkMetrics.realTimeFactor(1500.0, 3000), 0.0001)
        assertEquals(2.0, BenchmarkMetrics.median(listOf(3.0, 1.0)), 0.0001)
        assertEquals(2.0, BenchmarkMetrics.median(listOf(90.0, 2.0, 1.0)), 0.0001)
        try { BenchmarkMetrics.realTimeFactor(1.0, 0); fail() } catch (_: IllegalArgumentException) { }
        try { BenchmarkMetrics.median(listOf(Double.NaN)); fail() } catch (_: IllegalArgumentException) { }
    }
}
