package com.sal7one.common_jni.speech

/** A source/target pair is explicit. Supporting Arabic output does not imply every source language. */
data class TranslationDirection(val source: String, val target: String) {
    init {
        require(source.matches(Regex("[a-z]{2,3}")) && target.matches(Regex("[a-z]{2,3}")) && source != target)
    }
}
interface SpeechTextTranslator {
    val id: String
    val directions: Set<TranslationDirection>
    /** Return translated text or throw the original provider/native failure. No source-text fallback. */
    suspend fun translate(text: String, direction: TranslationDirection): String
}

data class CaptionText(val source: SpeechTranscript, val translatedText: String?, val targetLanguage: String?)

/**
 * Recognition -> text translation composition for the future caption consumer.
 * No provider, network client or translation model is implicitly selected/downloaded.
 * Direct translation is preferred; a deliberate English pivot is permitted only
 * when BOTH language pairs are supplied. The existing en->ar Marian model alone
 * cannot translate ru/zh transcripts, and fails here before any inference call.
 */
class SpeechCaptionTranslator(private val translators: List<SpeechTextTranslator>) {
    suspend fun caption(final: SpeechTranscript, targetLanguage: String? = null): CaptionText {
        require(final.isFinal) { "Translate completed utterances; partial revisions are replaceable" }
        if (targetLanguage == null) return CaptionText(final, null, null)
        val source = final.sourceLanguage?.let(SpeechLanguage::normalize)
            ?: throw IllegalStateException("Speech recognizer did not identify the source language")
        val target = SpeechLanguage.normalize(targetLanguage)
        require(source.matches(Regex("[a-z]{2,3}")) && target.matches(Regex("[a-z]{2,3}"))) { "Translation requires explicit supported language codes" }
        if (source == target) return CaptionText(final, null, null)
        val route = route(source, target)
        var text = final.text
        if (text.isBlank()) return CaptionText(final, "", target)
        for ((engine, direction) in route) {
            val translated = engine.translate(text, direction)
            check(translated.isNotBlank()) { "${engine.id} returned empty translation for $direction" }
            text = translated
        }
        return CaptionText(final, text, target)
    }
    fun canTranslate(source: String, target: String): Boolean = try {
        val from = SpeechLanguage.normalize(source); val to = SpeechLanguage.normalize(target)
        if (from == to && from.matches(Regex("[a-z]{2,3}"))) true else { route(from, to); true }
    } catch (_: IllegalArgumentException) { false }

    private fun route(source: String, target: String): List<Pair<SpeechTextTranslator, TranslationDirection>> {
        val direct = TranslationDirection(source, target)
        translators.firstOrNull { direct in it.directions }?.let { return listOf(it to direct) }
        if (source != "en" && target != "en") {
            val first = TranslationDirection(source, "en"); val second = TranslationDirection("en", target)
            val a = translators.firstOrNull { first in it.directions }
            val b = translators.firstOrNull { second in it.directions }
            if (a != null && b != null) return listOf(a to first, b to second)
        }
        throw IllegalArgumentException("No text translation route for $source -> $target")
    }
}
