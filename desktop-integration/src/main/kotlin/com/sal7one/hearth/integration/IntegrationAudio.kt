package com.sal7one.hearth.integration

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A bounded upload becomes the exact format accepted by PipelineInput.AudioFile. */
internal object IntegrationAudio {
    const val MAX_SECONDS = 120
    const val MAX_PCM_BYTES = MAX_SECONDS * 16_000 * 2
    const val MAX_UPLOAD_BYTES = MAX_PCM_BYTES + 65_536

    fun canonicalWave(input: ByteArray, rawPcm: Boolean): ByteArray {
        val pcm = if (rawPcm) input else readWave(input)
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= MAX_PCM_BYTES) {
            "Audio must contain up to 120 seconds of little-endian mono PCM16 at 16000 Hz (nonempty whole samples)"
        }
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
        out.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        out.putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
        return out.array()
    }

    private fun readWave(input: ByteArray): ByteArray {
        require(input.size in 44..MAX_UPLOAD_BYTES) { "Invalid or oversized WAV upload" }
        fun tag(offset: Int) = String(input, offset, 4, Charsets.US_ASCII)
        fun unsigned(offset: Int) = ByteBuffer.wrap(input, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun short(offset: Int) = ByteBuffer.wrap(input, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(tag(0) == "RIFF" && tag(8) == "WAVE" && unsigned(4) + 8 == input.size.toLong()) {
            "Expected a complete RIFF/WAVE file; compressed/RF64 formats are unsupported"
        }
        var offset = 12
        var format = false
        var pcm: ByteArray? = null
        while (offset < input.size) {
            require(offset + 8 <= input.size) { "Truncated WAV chunk header" }
            val size = unsigned(offset + 4)
            val end = offset.toLong() + 8 + size
            val paddedEnd = end + size % 2
            require(end <= input.size && paddedEnd <= input.size) { "Truncated WAV chunk" }
            when (tag(offset)) {
                "fmt " -> {
                    require(!format && size >= 16) { "Invalid or duplicate WAV format" }
                    require(short(offset + 8) == 1 && short(offset + 10) == 1 && unsigned(offset + 12) == 16_000L &&
                        unsigned(offset + 16) == 32_000L && short(offset + 20) == 2 && short(offset + 22) == 16) {
                        "Unsupported WAV codec: use uncompressed little-endian mono PCM16 at 16000 Hz"
                    }
                    format = true
                }
                "data" -> {
                    require(pcm == null && size in 2..MAX_PCM_BYTES.toLong() && size % 2 == 0L) { "Invalid, duplicate or oversized WAV data" }
                    pcm = input.copyOfRange(offset + 8, end.toInt())
                }
            }
            offset = paddedEnd.toInt()
        }
        require(format && pcm != null) { "WAV needs one PCM format and one audio data chunk" }
        return pcm
    }
}
