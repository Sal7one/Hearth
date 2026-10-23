package com.sal7one.transiber.benchmark

import com.sal7one.hearth.tts.ArticleReader
import com.sal7one.hearth.tts.ArticleSpeech
import com.sal7one.hearth.tts.VoicePreference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

internal data class BenchmarkVoiceMeasurement(val audioMs: Double, val firstAudioMs: Double)

/** Consume SDK backpressure frames directly; speaker playback and growing PCM buffers are excluded. */
internal object BenchmarkVoiceProbe {
    suspend fun measure(reader: ArticleReader, text: String, language: String, voice: String?,
        startedNanos: Long = System.nanoTime()): BenchmarkVoiceMeasurement {
        var audioMs = 0.0
        var firstAudioMs: Double? = null
        var audible = false
        reader.read(text, language, VoicePreference(id = voice)).collect { event ->
            currentCoroutineContext().ensureActive()
            if (event is ArticleSpeech.Audio) {
                if (firstAudioMs == null) firstAudioMs = (System.nanoTime() - startedNanos) / 1e6
                audioMs += event.pcm.samples.size * 1000.0 / event.pcm.sampleRate
                if (event.pcm.samples.any { kotlin.math.abs(it) > 1e-7f }) audible = true
            }
        }
        check(audioMs > 0 && audible) { "Voice generated no audible PCM" }
        return BenchmarkVoiceMeasurement(audioMs, checkNotNull(firstAudioMs))
    }
}
