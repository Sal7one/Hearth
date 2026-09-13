package com.sal7one.transiber.voice

import java.util.Locale

/** Select only installed, local voices matching the requested language. */
internal object OfflineVoicePolicy {
    data class Candidate(val id: String, val locale: Locale, val networkRequired: Boolean, val quality: Int)
    fun select(voices: List<Candidate>, requested: Locale): String? {
        if (requested.language.isBlank() || requested.language == "und") return null
        val preferredCountry = requested.country.ifBlank { if (requested.language == "ar") "SA" else "" }
        return voices.asSequence().filter { !it.networkRequired && it.locale.language == requested.language }
            .sortedWith(compareByDescending<Candidate> { preferredCountry.isNotBlank() && it.locale.country == preferredCountry }
                .thenByDescending { it.quality }.thenBy { it.id }).firstOrNull()?.id
    }
}
