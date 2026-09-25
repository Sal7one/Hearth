package com.sal7one.transiber.sign

/**
 * Sign languages this app can fingerspell. Display names and capability
 * lines are user-facing and deliberately conservative: the engines
 * recognize static single-letter handshapes only, so no sentence-level
 * understanding is claimed for any language.
 *
 * Strings are hardcoded English for now (res/ is owned by another change).
 */
internal enum class SignLanguage(
    /** Stable storage/selection identifier. */
    val id: String,
    /** Honest user-facing name. */
    val displayName: String,
    /** One-line, non-overstated description of what the engine can do. */
    val capability: String,
) {
    /** Static one-letter handshapes from the American Manual Alphabet. */
    ASL_ENGLISH(
        id = "asl",
        displayName = "ASL alphabet (English letters)",
        capability = "Fingerspelling only: recognizes one static letter handshape at a time. No full ASL sentences or grammar.",
    ),

    /**
     * Static one-letter handshapes trained on the AASL and ArSL2018
     * datasets, both collected in Saudi Arabia. This is alphabet-level
     * fingerspelling — NOT Saudi sentence-level sign language.
     */
    ARABIC(
        id = "arsl",
        displayName = "Arabic fingerspelling (AASL + ArSl2018, collected in Saudi Arabia)",
        capability = "Fingerspelling only: recognizes one static Arabic letter handshape at a time. No Saudi sentence-level sign language.",
    );

    companion object {
        /** Looks a language up by [SignLanguage.id]; null when unknown. */
        fun fromId(id: String): SignLanguage? = entries.firstOrNull { it.id == id }
    }
}
