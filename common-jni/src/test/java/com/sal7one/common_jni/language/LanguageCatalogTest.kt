package com.sal7one.common_jni.language

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationLanguages
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LanguageCatalogTest {
    @Test fun findsNativeNamesEnglishCodesAndAccents() {
        val codes = setOf("auto", "ar", "ru", "es", "zh", "pt")
        assertEquals("ar", LanguageCatalog.choices(codes, "العربية").single().code)
        assertEquals("ru", LanguageCatalog.choices(codes, "Russian").single().code)
        assertEquals("es", LanguageCatalog.choices(codes, "espanol").single().code)
        assertEquals("pt", LanguageCatalog.choices(codes, "portugues").single().code)
        assertEquals("auto", LanguageCatalog.choices(codes).first().code)
        assertTrue(LanguageCatalog.choices(codes, "unknown language").isEmpty())
    }
    @Test fun catalogDoesNotLimitNewModelCoverage() {
        val codes = LanguageCatalog.whisperCodes + TranslationLanguages.hyLanguages +
            SpeechProfile.entries.flatMap { it.capabilities.sourceLanguages }
        codes.forEach { code ->
            val option = LanguageCatalog.option(code)
            assertEquals(code, option.code)
            assertTrue(option.nativeName.isNotBlank())
            assertTrue(option.englishName.isNotBlank())
        }
        assertEquals("zz", LanguageCatalog.option("zz").code)
        assertTrue(LanguageCatalog.option("ar").rtl)
        assertFalse(LanguageCatalog.option("zh").rtl)
    }
    @Test fun searchIsIndependentOfPhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("hi", LanguageCatalog.choices(setOf("hi", "en"), "HINDI").single().code)
        } finally { Locale.setDefault(previous) }
    }
}
