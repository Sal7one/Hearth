package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import org.junit.Assert.*
import org.junit.Test

class TranslationSourceEvidenceTest {
    private val directions = setOf(TranslationDirection("ru", "ar"), TranslationDirection("fil", "ar"))

    @Test fun missingNemotronTagUsesSelectedRussianRouteOnlyForCyrillicText() {
        assertEquals("ru", TranslationSourceEvidence.resolve(null, "Это русская речь", "ar", directions))
        assertEquals("ru", TranslationSourceEvidence.resolve("mul", "Вот Russian слово", "ar", directions))
        assertNull(TranslationSourceEvidence.resolve(null, "This is English speech", "ar", directions))
        assertNull(TranslationSourceEvidence.resolve(null, "Это русская речь", "en", directions))
    }

    @Test fun ambiguousScriptsAndExplicitMetadataKeepTheirMeaning() {
        val ambiguous = directions + TranslationDirection("uk", "ar")
        assertNull(TranslationSourceEvidence.resolve("mul", "Привіт світ", "ar", ambiguous))
        assertEquals("en", TranslationSourceEvidence.resolve("en-US", "Это русская речь", "ar", directions))
        assertNull(TranslationSourceEvidence.resolve("auto", "Да", "ar", setOf(TranslationDirection("en", "ar"))))
    }
}
