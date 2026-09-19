package com.sal7one.transiber.caption

import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import org.junit.Assert.*
import org.junit.Test

class CaptionTranslationRouteTest {
    @Test fun liveArabicNeverDivertsEnglishRussianChineseOrAutoToLocalModel() {
        for (language in listOf("en", "ru", "zh", "auto")) {
            val c = CaptionOverlayConfig(engine = CaptionEngineChoice.CLOUD, mode = CaptionMode.TRANSLATE,
                target = TranslationTarget.ARABIC, streamLanguage = language)
            assertEquals(CaptionTranslationRoute.LIVE_TARGET, captionTranslationRoute(c, SttMode.STREAMING_OPENAI))
        }
    }
    @Test fun whisperArabicAlwaysUsesEnglishPivotForMarian() {
        for (engine in listOf(CaptionEngineChoice.WHISPER, CaptionEngineChoice.CLOUD)) {
            for (language in listOf("ru", "zh", "en", "auto")) {
                val route = captionTranslationRoute(CaptionOverlayConfig(engine = engine, mode = CaptionMode.TRANSLATE,
                    target = TranslationTarget.ARABIC, streamLanguage = language), SttMode.BATCH)
                assertTrue(route.sttToEnglish); assertTrue(route.secondStage)
            }
        }
    }
    @Test fun ccNeverEnablesTranslation() {
        for (engine in CaptionEngineChoice.entries) for (mode in SttMode.entries) {
            assertEquals(CaptionTranslationRoute.ORIGINAL, captionTranslationRoute(
                CaptionOverlayConfig(engine = engine, mode = CaptionMode.CAPTIONS, target = TranslationTarget.ARABIC), mode))
        }
    }
    @Test fun unsupportedSourceNeverFeedsRussianOrChineseIntoEnglishOnlyModel() {
        for (language in listOf("ru", "zh", "auto")) {
            assertEquals(CaptionTranslationRoute.UNSUPPORTED, captionTranslationRoute(CaptionOverlayConfig(
                engine = CaptionEngineChoice.CLOUD, mode = CaptionMode.TRANSLATE, target = TranslationTarget.ARABIC,
                streamLanguage = language), SttMode.STREAMING_DEEPGRAM))
        }
    }
    @Test fun forcedRussianUsesLocalTranslatorAndProviderTranslationKeepsPriority() {
        val c = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON, mode = CaptionMode.TRANSLATE,
            streamLanguage = "ru", target = TranslationTarget.ARABIC,
            localTranslationEnabled = true, localTranslationModelId = "ml-kit")
        assertEquals("ru", CaptionLanguages.effectiveSource(c, SttMode.BATCH))
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(c, SttMode.BATCH))
        for (provider in listOf(SttMode.STREAMING_OPENAI, SttMode.STREAMING_SONIOX)) {
            val cloud = c.copy(engine = CaptionEngineChoice.CLOUD)
            assertEquals(CaptionTranslationRoute.LIVE_TARGET, captionTranslationRoute(cloud, provider))
            assertFalse(CaptionLanguages.target(cloud, provider).note.contains("ML Kit"))
        }
        val scribe = c.copy(engine = CaptionEngineChoice.CLOUD)
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(scribe, SttMode.STREAMING_ELEVENLABS))
        assertEquals("ru", CaptionLanguages.effectiveSource(scribe, SttMode.STREAMING_ELEVENLABS))
        val legacy = c.copy(engine = CaptionEngineChoice.WHISPER)
        assertEquals(setOf("en", "ar"), CaptionLanguages.target(legacy, SttMode.BATCH).codes)
    }
    @Test fun moonshineAlwaysUsesFixedEnglishRegardlessOfRememberedLanguage() {
        val c = CaptionOverlayConfig(engine = CaptionEngineChoice.MOONSHINE, streamLanguage = "ru")
        assertEquals(setOf("en"), CaptionLanguages.source(c, SttMode.BATCH).codes)
        assertEquals("en", CaptionLanguages.effectiveSource(c, SttMode.BATCH))
    }
}
