package com.sal7one.transiber.caption

import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import com.sal7one.transiber.translation.*
import org.junit.Assert.*
import org.junit.Test

class CaptionTextTranslatorTest {
    private fun explicit(provider: String) = CaptionOverlayConfig(mode=CaptionMode.TRANSLATE,
        engine=CaptionEngineChoice.NEMOTRON, streamLanguage="ru", target=TranslationTarget.ARABIC,
        localTranslationEnabled=true, textTranslationProviderId=provider)

    @Test fun allTextProvidersRouteEverySpeechEngineThroughOriginalText() {
        for(provider in TextTranslationProvider.entries) for(engine in CaptionEngineChoice.entries) for(mode in SttMode.entries) {
            val route=captionTranslationRoute(explicit(provider.id).copy(engine=engine),mode)
            assertEquals("$provider / $engine / $mode",CaptionTranslationRoute.TEXT_TRANSLATOR,route)
            assertFalse(route.sttToEnglish)
            assertFalse(route.secondStage)
        }
    }
    @Test fun explicitLocalSupportsWhisperAndBatchWithoutEnglishPivot() {
        for(engine in listOf(CaptionEngineChoice.WHISPER,CaptionEngineChoice.CLOUD)) {
            val route=captionTranslationRoute(explicit("local").copy(engine=engine,localTranslationModelId="hy-mt2-q4"),SttMode.BATCH)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,route)
            assertFalse(route.sttToEnglish)
        }
    }
    @Test fun savedTextProviderNeverTurnsOnTranslationInCcMode() {
        for(provider in TextTranslationProvider.entries) for(mode in SttMode.entries) {
            assertEquals(CaptionTranslationRoute.ORIGINAL,captionTranslationRoute(explicit(provider.id).copy(mode=CaptionMode.CAPTIONS),mode))
        }
    }
    @Test fun noExplicitOverridePreservesIntegratedCloudTranslation() {
        for(mode in listOf(SttMode.STREAMING_OPENAI,SttMode.STREAMING_SONIOX)) {
            val c=explicit("google").copy(engine=CaptionEngineChoice.CLOUD,textTranslationProviderId="")
            assertEquals(CaptionTranslationRoute.LIVE_TARGET,captionTranslationRoute(c,mode))
        }
    }
    @Test fun cloudTargetsFollowServerDirectionsAndForcedSource() {
        val caps=CloudTranslationLanguages(mapOf("ru" to "ru","en" to "en"),mapOf("ar" to "ar","ja" to "ja"),
            mapOf("ru" to setOf("ar"),"en" to setOf("ja")))
        assertEquals(setOf("ar"),CaptionLanguages.target(explicit("libretranslate"),SttMode.BATCH,caps).codes)
        assertEquals(setOf("ja"),CaptionLanguages.target(explicit("libretranslate").copy(streamLanguage="en"),SttMode.BATCH,caps).codes)
        assertEquals(setOf("ar","ja"),CaptionLanguages.target(explicit("libretranslate").copy(streamLanguage="auto"),SttMode.BATCH,caps).codes)
    }
    @Test fun uncheckedCloudCapabilitiesNeverAdvertiseInventedLanguages() {
        assertTrue(CaptionLanguages.target(explicit("google"),SttMode.BATCH).codes.isEmpty())
    }
    @Test fun explicitLocalTargetsUseItsActualDirections() {
        val c=explicit("local").copy(localTranslationModelId=TranslationOptions.ML_KIT)
        assertEquals(PlatformTranslation.available, "ar" in CaptionLanguages.target(c,SttMode.BATCH).codes)
        assertFalse("ru" in CaptionLanguages.target(c,SttMode.BATCH).codes)
        assertTrue(CaptionLanguages.target(c.copy(streamLanguage="unsupported"),SttMode.BATCH).codes.isEmpty())
    }
    @Test fun explicitProviderSurvivesPreferencesAndOldInstallsKeepTheirRoute() {
        val prefs=androidx.datastore.preferences.core.mutablePreferencesOf()
        assertEquals("",CaptionConfigStore.readFrom(prefs).textTranslationProviderId)
        val c=explicit("deepl")
        CaptionConfigStore.writeInto(prefs,c)
        val restored=CaptionConfigStore.readFrom(prefs)
        assertEquals("deepl",restored.textTranslationProviderId)
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,captionTranslationRoute(restored,SttMode.BATCH))
    }
    @Test fun sourceValidatedForActualWhisperWeightsSurvivesFinalPromotion() {
        val config=explicit("deepl").copy(engine=CaptionEngineChoice.WHISPER)
        val resolved=CaptionLanguages.effectiveSource(config,SttMode.BATCH,CaptionLanguageModel("test","Whisper",51865))
        assertEquals("ru",resolved)
        assertEquals("ru",captionTranslationSource(resolved,"en"))
        assertEquals("ja",captionTranslationSource("auto","ja"))
        assertNull(captionTranslationSource("auto",null))
    }
    @Test fun explicitTextStageAllowsCloudTranscriptionLanguageHints() {
        val c=explicit("deepl").copy(engine=CaptionEngineChoice.CLOUD)
        for(mode in listOf(SttMode.STREAMING_OPENAI,SttMode.BATCH)) {
            val source=CaptionLanguages.source(c,mode,CaptionLanguageModel("whisper-1"))
            assertTrue("ru" in source.codes)
        }
    }
    @Test fun localCapabilityCheckRejectsUnknownModelAndSameLanguage() {
        assertFalse(TranslationOptions.supports("unknown","ru","ar"))
        assertFalse(TranslationOptions.supports(TranslationOptions.ML_KIT,"ru","ru"))
        assertEquals(PlatformTranslation.available, TranslationOptions.supports(TranslationOptions.ML_KIT,"ru","ar"))
    }
}
