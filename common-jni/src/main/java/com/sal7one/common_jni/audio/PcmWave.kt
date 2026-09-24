package com.sal7one.common_jni.audio

/** Strict RIFF PCM16 parsing shared by imported benchmark audio and remote TTS. */
object PcmWave {
    data class Audio(val samples: ShortArray,val sampleRate: Int)
    fun decode(bytes: ByteArray,maxBytes: Int=12*1024*1024,maxSeconds: Int=120): Audio {
        fun text(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        fun u32(at: Int) = (0..3).fold(0L) { n, i -> n or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
        require(bytes.size in 44..maxBytes && text(0) == "RIFF" && text(8) == "WAVE") { "Choose an uncompressed PCM16 WAV file" }
        val end = u32(4) + 8
        require(end == bytes.size.toLong()) { "WAV length does not match its header" }
        var rate = 0; var channels = 0; var data: IntRange? = null; var hasFormat = false; var offset = 12
        while (offset < bytes.size) {
            require(offset + 8 <= bytes.size) { "Truncated WAV chunk header" }
            val count = u32(offset + 4)
            val start = offset + 8
            require(count <= bytes.size.toLong() - start) { "Truncated WAV chunk" }
            when (text(offset)) {
                "fmt " -> {
                    require(!hasFormat && count >= 16) { "Invalid or repeated WAV format" }
                    val format = u16(start)
                    require((format == 1 || format == 0xfffe) && u16(start + 14) == 16) {
                        "WAV must use uncompressed 16-bit PCM"
                    }
                    channels = u16(start + 2); rate = u32(start + 4).toInt()
                    require(channels in 1..2 && rate in 8000..48000) { "WAV must be mono/stereo at 8–48 kHz" }
                    require(u16(start + 12) == channels * 2 && u32(start + 8) == rate.toLong() * channels * 2) { "Invalid WAV frame alignment" }
                    if (format == 0xfffe) {
                        require(count >= 40 && u16(start + 16) >= 22 &&
                            18L + u16(start + 16) <= count) { "Invalid extensible WAV format" }
                        require(u16(start + 18) in 1..16) { "Invalid PCM valid-bit count" }
                        val mask = u32(start + 20)
                        require(mask == 0L || java.lang.Long.bitCount(mask) == channels) {
                            "Invalid WAV channel mask"
                        }
                        val pcmGuid = byteArrayOf(
                            1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0,
                            0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
                        require((0 until 16).all { bytes[start + 24 + it] == pcmGuid[it] }) {
                            "WAV extensible subformat must be PCM"
                        }
                    }
                    hasFormat = true
                }
                "data" -> {
                    require(data == null) { "Repeated WAV audio chunk" }
                    data = start until (start + count.toInt())
                }
            }
            val next = start.toLong() + count + (count and 1)
            val unpaddedEnd = start.toLong() + count
            require(next <= bytes.size || unpaddedEnd == bytes.size.toLong()) {
                "Missing WAV chunk padding"
            }
            offset = minOf(next, bytes.size.toLong()).toInt()
        }
        require(hasFormat && data != null && !data.isEmpty()) { "WAV has no PCM audio" }
        val range = data
        val size = range.last - range.first + 1
        require(size % (channels * 2) == 0) { "WAV ends inside an audio frame" }
        val frames = size / (channels * 2)
        require(frames > 0 && frames <= rate * maxSeconds) { "WAV duration exceeds the allowed limit" }
        val mono = ShortArray(frames) { frame ->
            val at = range.first + frame * channels * 2
            val a = u16(at).toShort().toInt()
            if (channels == 1) a.toShort() else ((a + u16(at + 2).toShort().toInt()) / 2).toShort()
        }
        return Audio(mono,rate)
    }
}
