package com.sal7one.hearth.tts.ming

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict parser for the only output the Ming runtime produces: mono 44.1 kHz PCM16 WAV. */
internal object MingWav {
    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun decode(bytes: ByteArray): Pcm {
        require(
            bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE",
        ) { "Ming returned a non-WAV file" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var format = -1
        var channels = -1
        var rate = -1
        var bits = -1
        var dataStart = -1
        var dataSize = -1
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            require(size >= 0 && body.toLong() + size <= bytes.size) { "Ming returned a malformed WAV" }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Ming returned a short WAV format chunk" }
                    format = buffer.getShort(body).toInt()
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> { dataStart = body; dataSize = size }
            }
            offset = body + size + (size and 1)
        }
        require(
            format == 1 && channels == 1 && rate == MingCatalog.SAMPLE_RATE && bits == 16 &&
                dataStart >= 0 && dataSize > 0 && dataSize % 2 == 0,
        ) { "Ming must return mono 44.1 kHz PCM16 WAV" }
        val samples = FloatArray(dataSize / 2)
        var index = 0
        while (index < samples.size) {
            samples[index] = buffer.getShort(dataStart + index * 2) / 32768f
            index++
        }
        return Pcm(samples, rate)
    }
}
