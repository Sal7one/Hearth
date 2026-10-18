package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection

/** Recover a missing ASR language only when the chosen translator and caption script agree. */
internal object TranslationSourceEvidence {
    fun resolve(source: String?, text: String, target: String, directions: Set<TranslationDirection>): String? {
        val reported = source?.let(TranslationLanguages::normalize)
        if (reported != null && reported !in setOf("", "auto", "und", "mul")) return reported
        val scripts = text.codePoints().toArray().asSequence()
            .filter(Character::isLetter)
            .map(Character.UnicodeScript::of).toList()
        if (scripts.size < 2) return null
        val candidates = directions.asSequence().filter { it.target == target }
            .map { it.source }.distinct().filter { candidate ->
                val matching = scripts.count { script -> matches(candidate, script) }
                matching * 2 > scripts.size
            }.toList()
        return candidates.singleOrNull()
    }

    private fun matches(language: String, script: Character.UnicodeScript): Boolean =
        IndexTranslationLanguages.matchesScript(language, script)
}
