package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.audio.Pcm16Resampler
import java.io.InputStream
import java.security.MessageDigest

/** Strict bounded input, decoded once so every recognizer receives the same PCM. */
internal object BenchmarkAudio {
    const val MAX_SECONDS = 30
    const val MAX_BYTES = 6 * 1024 * 1024
    const val NORMALIZATION = "peak normalize to 0.90 when source peak < 0.50; digital silence is unchanged"
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
        val audio=com.sal7one.common_jni.audio.PcmWave.decode(bytes,MAX_BYTES,MAX_SECONDS)
        val mono=audio.samples;val rate=audio.sampleRate
        require(mono.size>=rate/2) { "Choose audio between 0.5 and 30 seconds" }
        val samples = Pcm16Resampler.frame(mono, rate, 16000)
        val digest = MessageDigest.getInstance("SHA-256")
        samples.forEach { digest.update(it.toByte()); digest.update((it.toInt() shr 8).toByte()) }
        return Clip(samples, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    /** Match the Mac harness and keep quiet publisher recordings above the segmenter's energy gate. */
    fun normalizeForComparison(samples: ShortArray): ShortArray {
        val peak = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val peakFloat = peak / 32768f
        if (peakFloat <= 0.0001f || peakFloat >= 0.5f) return samples
        val gain = 0.9f / peakFloat
        return ShortArray(samples.size) { index ->
            (samples[index] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
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
