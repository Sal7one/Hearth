package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.audio.Pcm16Resampler
import java.io.InputStream
import java.security.MessageDigest

/** Strict bounded input, decoded once so every recognizer receives the same PCM. */
internal object BenchmarkAudio {
    const val MAX_SECONDS = 30
    const val MAX_BYTES = 6 * 1024 * 1024
    data class Clip(val samples: ShortArray, val sha256: String) {
        val durationMs: Long get() = samples.size * 1000L / 16000
    }
    fun read(input: InputStream): Clip {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Choose a WAV of 30 seconds or less (maximum 6 MiB)" }
            output.write(buffer, 0, count)
        }
        return decode(output.toByteArray())
    }
    fun decode(bytes: ByteArray): Clip {
        fun text(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        fun u32(at: Int) = (0..3).fold(0L) { n, i -> n or ((bytes[at + i].toLong() and 255) shl (8 * i)) }
        require(bytes.size in 44..MAX_BYTES && text(0) == "RIFF" && text(8) == "WAVE") { "Choose an uncompressed PCM16 WAV file" }
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
                    require(u16(start) == 1 && u16(start + 14) == 16) { "WAV must use uncompressed 16-bit PCM" }
                    channels = u16(start + 2); rate = u32(start + 4).toInt()
                    require(channels in 1..2 && rate in 8000..48000) { "WAV must be mono/stereo at 8–48 kHz" }
                    require(u16(start + 12) == channels * 2 && u32(start + 8) == rate.toLong() * channels * 2) { "Invalid WAV frame alignment" }
                    hasFormat = true
                }
                "data" -> {
                    require(data == null) { "Repeated WAV audio chunk" }
                    data = start until (start + count.toInt())
                }
            }
            val next = start.toLong() + count + (count and 1)
            require(next <= bytes.size) { "Missing WAV chunk padding" }
            offset = next.toInt()
        }
        require(hasFormat && data != null && !data.isEmpty()) { "WAV has no PCM audio" }
        val range = data
        val size = range.last - range.first + 1
        require(size % (channels * 2) == 0) { "WAV ends inside an audio frame" }
        val frames = size / (channels * 2)
        require(frames >= rate / 2 && frames <= rate * MAX_SECONDS) { "Choose audio between 0.5 and 30 seconds" }
        val mono = ShortArray(frames) { frame ->
            val at = range.first + frame * channels * 2
            val a = u16(at).toShort().toInt()
            if (channels == 1) a.toShort() else ((a + u16(at + 2).toShort().toInt()) / 2).toShort()
        }
        val samples = Pcm16Resampler.frame(mono, rate, 16000)
        val digest = MessageDigest.getInstance("SHA-256")
        samples.forEach { digest.update(it.toByte()); digest.update((it.toInt() shr 8).toByte()) }
        return Clip(samples, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}

internal object BenchmarkMetrics {
    fun realTimeFactor(computeMs: Double, audioMs: Long): Double {
        require(computeMs >= 0 && computeMs.isFinite() && audioMs > 0)
        return computeMs / audioMs
    }
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty() && values.all { it.isFinite() && it >= 0 })
        val sorted = values.sorted(); val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
