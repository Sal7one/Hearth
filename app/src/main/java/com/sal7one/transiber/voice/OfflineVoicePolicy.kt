package com.sal7one.transiber.voice

import java.util.Locale

/** Select only installed, local voices matching the requested language. */
internal object OfflineVoicePolicy {
    data class Candidate(val id: String, val locale: Locale, val networkRequired: Boolean, val quality: Int)
    fun selectForReadAloud(voices: List<Candidate>, requested: Locale, saved: String?, gender: VoiceGender): String? {
        val eligible = voices.filter { !it.networkRequired && it.locale.language == requested.language }
        check(saved == null || eligible.any { it.id == saved }) {
            "Selected Android ${requested.toLanguageTag()} voice is unavailable. Choose a voice in Voice settings"
        }
        val preferred = eligible.filter { VoiceGenderMapping.systemVoiceMatches(it.id, gender) }.ifEmpty { eligible }
        return saved?.takeIf { id -> preferred.any { it.id == id } } ?: select(preferred, requested)
    }
    fun select(voices: List<Candidate>, requested: Locale): String? {
        if (requested.language.isBlank() || requested.language == "und") return null
        val preferredCountry = requested.country.ifBlank { if (requested.language == "ar") "SA" else "" }
        return voices.asSequence().filter { !it.networkRequired && it.locale.language == requested.language }
            .sortedWith(compareByDescending<Candidate> { preferredCountry.isNotBlank() && it.locale.country == preferredCountry }
                .thenByDescending { it.quality }.thenBy { it.id }).firstOrNull()?.id
    }
}
