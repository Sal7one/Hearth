package com.sal7one.transiber.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ModelSearchTest {
    @Test fun everyWordCanMatchASeparateFieldWithoutDependingOnOrderOrPunctuation() {
        assertTrue(ModelSearch.matches("ARABIC whisper q5", "Whisper tiny", "Q5_1", ModelSearch.languageTerms(setOf("ar"))))
        assertTrue(ModelSearch.matches("qwen3 asr", "Qwen3-ASR 0.6B"))
        assertFalse(ModelSearch.matches("whisper Japanese", "Whisper tiny.en", ModelSearch.languageTerms(setOf("en"))))
    }

    @Test fun languageNamesSupportNativeScriptsAliasesAndAccents() {
        assertTrue(ModelSearch.matches("العَرَبِيَّة", ModelSearch.languageTerms(setOf("ar"))))
        assertTrue(ModelSearch.matches("Mandarin", ModelSearch.languageTerms(setOf("zh"))))
        assertTrue(ModelSearch.matches("farsi", ModelSearch.languageTerms(setOf("fa"))))
        assertTrue(ModelSearch.matches("francais", ModelSearch.languageTerms(setOf("fr"))))
        assertTrue(ModelSearch.matches("中文", ModelSearch.languageTerms(setOf("zh"))))
    }

    @Test fun shortLanguageCodesDoNotMatchInsideUnrelatedWords() {
        assertTrue(ModelSearch.matches("en", "tiny.en"))
        assertFalse(ModelSearch.matches("en", "French encoder"))
        assertFalse(ModelSearch.matches("ar", "large Marian"))
        assertTrue(ModelSearch.matches("   ---   ", "Whisper"))
    }

    @Test fun allAppLanguageNamesRemainSearchableWhenTheSystemLanguageDiffers() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertTrue(ModelSearch.matches("الفرنسية", ModelSearch.languageTerms(setOf("fr"))))
            assertTrue(ModelSearch.matches("法语", ModelSearch.languageTerms(setOf("fr"))))
            assertTrue(ModelSearch.matches("العربية", ModelSearch.languageTerms(setOf("ar"))))
        } finally { Locale.setDefault(previous) }
    }
}
