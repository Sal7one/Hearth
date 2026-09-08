package com.sal7one.transiber.byok

import java.io.ByteArrayOutputStream

/**
 * Minimal 16-bit PCM WAV encoder for cloud STT uploads (BYOK).
 *
 * Pure Kotlin, no platform deps — unit-testable on the JVM. The cloud
 * Whisper APIs accept WAV directly, so no transcoding to Opus is worth
 * the complexity for 4-12 s utterances.
 */
object WavEncoder {

    /**
     * Encodes mono 16-bit PCM as a WAV container.
     * [samples] are signed 16-bit values at [sampleRate] Hz.
     */
    fun encodePcm16(samples: ShortArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        val dataSize = samples.size * 2
        val byteRate = sampleRate * 2

        out.writeAscii("RIFF")
        out.writeLeInt(36 + dataSize)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeLeInt(16)            // PCM chunk size
        out.writeLeShort(1)           // PCM format
        out.writeLeShort(1)           // mono
        out.writeLeInt(sampleRate)
        out.writeLeInt(byteRate)
        out.writeLeShort(2)           // block align
        out.writeLeShort(16)          // bits per sample
        out.writeAscii("data")
        out.writeLeInt(dataSize)

        val buf = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            buf[i * 2] = (v and 0xFF).toByte()
            buf[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        for (b in s.toByteArray(Charsets.US_ASCII)) write(b.toInt())
    }

    private fun ByteArrayOutputStream.writeLeInt(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLeShort(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
