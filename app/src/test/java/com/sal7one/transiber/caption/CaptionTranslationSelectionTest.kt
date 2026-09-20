package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import org.junit.Assert.*
import org.junit.Test

class CaptionTranslationSelectionTest {
    private val local = CaptionOverlayConfig(engine=CaptionEngineChoice.NEMOTRON,
        mode=CaptionMode.TRANSLATE, localTranslationModelId="hy-mt2-q4", target=TranslationTarget.ARABIC,
        streamLanguage="ru", localTranslationEnabled=false)

    @Test fun selectedLocalTranslatorSurvivesOldDisabledBridgeAndRestart() {
        for (engine in listOf(CaptionEngineChoice.NEMOTRON,CaptionEngineChoice.QWEN,CaptionEngineChoice.MOONSHINE)) {
            val broken=local.copy(engine=engine)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,captionTranslationRoute(broken,SttMode.BATCH))
            val prefs=mutablePreferencesOf()
            CaptionConfigStore.writeInto(prefs,broken) // The contradictory configuration written by older screens.
            val restored=CaptionConfigStore.readFrom(prefs)
            assertTrue(restored.localTranslationEnabled)
            assertEquals("hy-mt2-q4",restored.localTranslationModelId)
            assertEquals("ru",restored.streamLanguage)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,captionTranslationRoute(restored,SttMode.BATCH))
        }
    }
    @Test fun ccThenTranslateReactivatesSameTranslatorWithoutChangingLanguagesOrAppearance() {
        val old=local.copy(textTranslationProviderId="local",theme=CaptionTheme.LIGHT,bubbleHeightDp=190)
        val cc=old.withCaptionMode(CaptionMode.CAPTIONS)
        assertFalse(cc.localTranslationEnabled)
        assertEquals(CaptionTranslationRoute.ORIGINAL,captionTranslationRoute(cc,SttMode.BATCH))
        val translated=cc.withCaptionMode(CaptionMode.TRANSLATE)
        assertTrue(translated.localTranslationEnabled)
        assertEquals(old.copy(localTranslationEnabled=true),translated)
        assertTrue("ar" in CaptionLanguages.target(translated,SttMode.BATCH).codes)
    }
    @Test fun explicitLocalAndCloudTranslatorAgreeWithLanguagePickerEvenWithOldFalseFlag() {
        for (engine in CaptionEngineChoice.entries) {
            val c=local.copy(engine=engine,textTranslationProviderId="local")
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,captionTranslationRoute(c,SttMode.STREAMING_OPENAI))
            assertTrue("ar" in CaptionLanguages.target(c,SttMode.STREAMING_OPENAI).codes)
        }
        val cloud=local.copy(engine=CaptionEngineChoice.CLOUD,textTranslationProviderId="deepl")
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,captionTranslationRoute(cloud,SttMode.STREAMING_OPENAI))
        assertTrue(CaptionLanguages.target(cloud,SttMode.STREAMING_OPENAI).codes.isEmpty()) // Must still load verified provider capabilities.
    }
    @Test fun savedLocalWeightsDoNotOverrideIntegratedCloudOrEnableCcTranslation() {
        val cloud=local.copy(engine=CaptionEngineChoice.CLOUD)
        for(mode in listOf(SttMode.STREAMING_OPENAI,SttMode.STREAMING_SONIOX))
            assertEquals(CaptionTranslationRoute.LIVE_TARGET,captionTranslationRoute(cloud,mode))
        assertEquals(CaptionTranslationRoute.ENGLISH_PIVOT,captionTranslationRoute(cloud,SttMode.BATCH))
        assertEquals(CaptionTranslationRoute.ORIGINAL,captionTranslationRoute(local.copy(mode=CaptionMode.CAPTIONS),SttMode.BATCH))
    }
}
