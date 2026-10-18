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
    @Test fun broadTranslatorNeverConfusesASharedScriptWithALanguage() {
        val broad = TranslationCatalog.find("index-translate-2b-q4").directions
        for (text in listOf("هذه جملة عربية", "یہ اردو متن ہے", "Привіт світ", "你好世界", "שלום עולם", "नमस्ते दुनिया", "This is English")) {
            assertNull(text, TranslationSourceEvidence.resolve("auto", text, "en", broad))
        }
        assertEquals("ar", TranslationSourceEvidence.resolve("ar-SA", "هذه جملة عربية", "en", broad))
        assertEquals("sr-Latn", TranslationSourceEvidence.resolve("srp_Latn", "Dobar dan", "en", broad))
        assertEquals("ko", TranslationSourceEvidence.resolve(null, "안녕하세요 세계", "en", broad))
        assertNull(TranslationSourceEvidence.resolve("mul", "hello 你好 مرحبا", "en", broad))
    }
    @Test fun oldBroadModelsAlsoRetainSharedScriptAmbiguity() {
        val hy = TranslationCatalog.find("hy-mt2-q4").directions
        assertNull(TranslationSourceEvidence.resolve(null, "هذه جملة عربية", "en", hy))
        assertNull(TranslationSourceEvidence.resolve(null, "你好世界", "en", hy))
        assertEquals("zh", TranslationSourceEvidence.resolve(null, "你好世界", "en", setOf(TranslationDirection("zh", "en"))))
    }
}
