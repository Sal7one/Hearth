package com.sal7one.hearth.tts

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class VoiceGender { UNKNOWN, FEMALE, MALE }
enum class VoiceFallback { REQUIRE_MATCH, ALLOW_SAME_LANGUAGE }

/** Gender is publisher metadata, never inferred from a speaker name. */
data class SpeechVoice(val id: String, val label: String, val languages: Set<String>, val gender: VoiceGender = VoiceGender.UNKNOWN) {
    init { require(id.isNotBlank() && label.isNotBlank() && languages.isNotEmpty()) }
    fun supports(language: String): Boolean {
        val requested = Locale.forLanguageTag(language)
        require(requested.language.isNotBlank()) { "A valid speech language is required" }
        return languages.any { candidate ->
            candidate.equals(language, true) || (!candidate.contains('-') && candidate == requested.language)
        }
    }
}

data class VoicePreference(val id: String? = null, val gender: VoiceGender = VoiceGender.UNKNOWN,
                           val fallback: VoiceFallback = VoiceFallback.REQUIRE_MATCH)
data class VoiceResolution(val voice: SpeechVoice, val fallbackReason: String? = null)

object SpeechVoices {
    fun resolve(voices: List<SpeechVoice>, language: String, preference: VoicePreference): VoiceResolution {
        val candidates = voices.filter { it.supports(language) }
        require(candidates.isNotEmpty()) { "No installed voice supports $language" }
        val match = candidates.firstOrNull {
            (preference.id == null || it.id == preference.id) &&
                (preference.gender == VoiceGender.UNKNOWN || it.gender == preference.gender)
        }
        if (match != null) return VoiceResolution(match)
        require(preference.fallback == VoiceFallback.ALLOW_SAME_LANGUAGE) {
            "Requested voice or verified ${preference.gender.name.lowercase()} voice is unavailable for $language"
        }
        return VoiceResolution(candidates.first(), "Requested voice is unavailable; using ${candidates.first().label} for $language")
    }
}

data class SpeechOptions(val rate: Float = 1f, val qualitySteps: Int = 5) {
    init { require(rate.isFinite() && rate in .5f..2f); require(qualitySteps in 2..12) }
}

/** Per-article token also covers cancellation just before native construction begins. */
class SpeechCancellation internal constructor() {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()
    fun throwIfCancelled() { if (isCancelled) throw CancellationException("Article speech stopped") }
    internal fun cancel() { cancelled.set(true) }
}

/** One second maximum, mono float PCM. The producer owns this array until emit returns. */
data class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    init {
        require(sampleRate in 8000..96000) { "Unsupported PCM sample rate: $sampleRate" }
        require(samples.isNotEmpty() && samples.size <= sampleRate) { "PCM frames must contain 1–1000 ms of audio" }
        require(samples.all(Float::isFinite)) { "Engine returned non-finite PCM" }
    }
}

/** Optional runtimes implement this blocking API. cancel must be thread-safe and idempotent. */
interface SpeechEngine : AutoCloseable {
    val id: String
    val voices: List<SpeechVoice>
    val maximumTextCharacters: Int get() = 180
    fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit)
    fun cancel()
}
