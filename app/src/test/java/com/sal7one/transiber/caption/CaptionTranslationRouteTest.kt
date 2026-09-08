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
}
