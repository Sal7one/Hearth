package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.translation.TranslationOptions
import org.junit.Assert.*
import org.junit.Test

class IndexTranslationRoutingTest {
    @Test fun scriptSelectionSurvivesCapabilitiesAndPersistence() {
        for (target in listOf("sr-Latn", "uz-Cyrl", "hi-Latn", "ur-Latn", "ars")) {
            val config = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON,
                streamLanguage = "en", textTranslationProviderId = "local",
                localTranslationModelId = "index-translate-2b-q4", target = TranslationTarget.of(target))
            val choices = CaptionLanguages.target(config, CloudConfigStore.SttMode.BATCH)
            assertTrue(target, choices.accepts(target))
            assertTrue(TranslationOptions.supports(config.localTranslationModelId, "en", target))
            val preferences = mutablePreferencesOf()
            CaptionConfigStore.writeInto(preferences, config)
            assertEquals(target, CaptionConfigStore.readFrom(preferences).target.languageTag)
            assertEquals(config.target, TranslationTarget.fromStored(config.target.name))
        }
        assertEquals("Serbian (Latin)", com.sal7one.common_jni.language.LanguageCatalog.option("sr-Latn").englishName)
        assertFalse(TranslationOptions.supports("hy-mt2-q4", "en", "sr-Latn"))
        assertEquals(TranslationTarget.ARABIC, TranslationTarget.fromStored("ARABIC"))
        for (invalid in listOf("", "auto", "und", "mul", "zxx", "sr--Latn", "sr-latn", "<ar>")) {
            assertThrows(IllegalArgumentException::class.java) { TranslationTarget.of(invalid) }
        }
    }
}
