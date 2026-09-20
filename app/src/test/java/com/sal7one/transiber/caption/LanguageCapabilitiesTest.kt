package com.sal7one.transiber.caption

import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.byok.CloudSpeechLanguages
import com.sal7one.transiber.byok.OpenAiTranslateClient
import com.sal7one.transiber.setup.EasySetupPreset
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.ocr.*
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class LanguageCapabilitiesTest {
    @Test fun openAiSetupPickerPersistenceAndWireAgreeBeyondTheOriginalThree() {
        for (language in listOf("sv", "es", "fr", "de", "ru", "ja", "ar", "zh", "yue")) {
            val setup = EasySetupPreset.cloud(CaptionOverlayConfig(), CloudConfigStore.Provider.OPENAI, language)
            val choices = CaptionLanguages.target(setup, CloudConfigStore.SttMode.STREAMING_OPENAI)
            assertTrue(choices.accepts(language))
            assertTrue(language in choices.pickerCodes(language))
            val saved = mutablePreferencesOf()
            CaptionConfigStore.writeInto(saved, setup)
            assertEquals(language, CaptionConfigStore.readFrom(saved).target.languageTag)
            assertEquals(language, OpenAiTranslateClient.sessionUpdate(language)
                .getJSONObject("session").getJSONObject("audio").getJSONObject("output").getString("language"))
        }
    }
    @Test fun providerValidatedChoicesAreNotInheritedByLocalOrDiscoveredModels() {
        val local = CaptionOverlayConfig(engine=CaptionEngineChoice.NEMOTRON,
            textTranslationProviderId="local", localTranslationModelId="hy-mt2-q4")
        assertFalse(CaptionLanguages.target(local, CloudConfigStore.SttMode.BATCH).allowsLanguageCode)
        assertFalse(CaptionLanguages.target(local, CloudConfigStore.SttMode.BATCH).accepts("zz"))
        val remote = CloudTranslationLanguages(mapOf("ja" to "ja"), mapOf("en" to "en"))
        val choices = CaptionLanguages.target(local.copy(textTranslationProviderId="google",streamLanguage="ja"), CloudConfigStore.SttMode.BATCH, remote)
        assertEquals(setOf("en"), choices.codes)
        assertFalse(choices.accepts("fr"))
        assertNull(choices.customCode("fr"))
    }
    @Test fun unknownModelsNeverPretendToHaveHyLanguages() {
        for (provider in listOf("", "local")) {
            val config=CaptionOverlayConfig(engine=CaptionEngineChoice.NEMOTRON,
                localTranslationEnabled=true, textTranslationProviderId=provider, localTranslationModelId="future-model")
            assertTrue(CaptionLanguages.target(config, CloudConfigStore.SttMode.BATCH).codes.isEmpty())
        }
        assertTrue(TranslationOptions.languages("future-model").isEmpty())
    }
    @Test fun languageCodeEntryIsExplicitBoundedAndIndependentOfDisplayLocale() {
        val choices=CaptionLanguages.openAiTranslation
        assertTrue("sv" in choices.codes)
        assertEquals("yue", choices.customCode("  YUE  "))
        assertNull(choices.customCode("Swedish"))
        for (invalid in listOf("", "auto", "und", "mul", "zxx", "fr-FR", "fr\n", "<ar>")) {
            assertFalse(CloudSpeechLanguages.isExplicitLanguageCode(invalid))
            assertThrows(IllegalArgumentException::class.java) { OpenAiTranslateClient.sessionUpdate(invalid) }
        }
    }
    @Test fun runtimeMlKitCapabilitiesFlowToCaptionAndOcrAndStayEmptyInFoss() {
        assertEquals(PlatformTranslation.languages, TranslationOptions.mlKitCodes)
        assertEquals(PlatformTranslation.languages, TranslationOptions.languages(TranslationOptions.ML_KIT))
        val config=CaptionOverlayConfig(engine=CaptionEngineChoice.NEMOTRON,textTranslationProviderId="local",
            localTranslationModelId=TranslationOptions.ML_KIT,streamLanguage="auto")
        assertEquals(PlatformTranslation.languages,CaptionLanguages.target(config,CloudConfigStore.SttMode.BATCH).codes)
        val ocr=ocrTranslationTargets(OcrSelection(source="en"),TranslationOptions.ML_KIT,null,true)
        assertEquals(PlatformTranslation.languages - "en",ocr)
        assertEquals(PlatformTranslation.available, "ar" in PlatformTranslation.languages)
    }
    @Test fun discoveredDirectionsReachCaptionAndOcrWithoutAddingAnotherStaticList() {
        val discovered=CloudTranslationProtocol.parseLanguages(TextTranslationProvider.LIBRETRANSLATE,listOf("""[
            {"code":"ja","targets":["sv","fr"]}, {"code":"sv","targets":["ja"]}
        ]"""))
        val config=CaptionOverlayConfig(engine=CaptionEngineChoice.NEMOTRON,textTranslationProviderId="libretranslate",streamLanguage="ja")
        assertEquals(setOf("sv","fr"), CaptionLanguages.target(config,CloudConfigStore.SttMode.BATCH,discovered).codes)
        assertEquals(setOf("sv","fr"),ocrTranslationTargets(OcrSelection("manga","ja","ar","libretranslate"),"unknown",discovered,true))
        assertFalse(discovered.supports("sv","fr"))
    }

    @Test fun scribeUsesFullModelCoverageInsteadOfTheOldShortlist() {
        val source = CaptionLanguages.source(CaptionOverlayConfig(engine=CaptionEngineChoice.CLOUD),CloudConfigStore.SttMode.STREAMING_ELEVENLABS)
        for (code in listOf("ar", "he", "bn", "yue", "sw", "fil", "ur")) assertTrue(code in source.codes)
        assertFalse("zz" in source.codes)
        assertFalse(source.allowsLanguageCode)
    }
    @Test fun paddleLanguagesSelectTheMatchingPinnedReaderWithoutChangingTranslation() {
        val current = OcrSelection("manga", "ja", "ar")
        for (code in listOf("fi", "vi", "tr", "pl", "fil")) {
            val next = current.withSource(code)
            assertEquals("latin",next.profileId)
            assertEquals("ar",next.target)
        }
        for (code in listOf("fa", "ur", "ps", "sd")) assertEquals("arabic",current.withSource(code).profileId)
        assertEquals("arabic",OcrSelection("arabic", "ar", "en").withSource("en").profileId)
        assertEquals("meiki",current.withProfile("meiki").withSource("ja").profileId)
    }
}
