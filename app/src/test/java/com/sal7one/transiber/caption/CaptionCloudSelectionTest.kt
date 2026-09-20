package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import org.junit.Assert.*
import org.junit.Test

class CaptionCloudSelectionTest {
    private val previous = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON,
        mode = CaptionMode.TRANSLATE, localTranslationEnabled = true,
        textTranslationProviderId = "local", localTranslationModelId = "hy-mt2-q4",
        streamLanguage = "ru", target = TranslationTarget.of("sv"))

    @Test fun selectingIntegratedCloudReplacesOldOverrideInRoutingAndPickerAfterRestart() {
        for (mode in listOf(SttMode.STREAMING_OPENAI, SttMode.STREAMING_SONIOX)) {
            for (provider in listOf("local", "google", "deepl", "")) {
                val selected = previous.copy(textTranslationProviderId = provider).selectCaptionEngine(CaptionEngineChoice.CLOUD, mode)
                val prefs = mutablePreferencesOf()
                CaptionConfigStore.writeInto(prefs, selected)
                val restored = CaptionConfigStore.readFrom(prefs)
                assertEquals(CaptionTranslationRoute.LIVE_TARGET, captionTranslationRoute(restored, mode))
                assertEquals("", restored.textTranslationProviderId)
                assertFalse(restored.localTranslationEnabled)
                assertEquals(previous.localTranslationModelId, restored.localTranslationModelId)
                assertEquals(previous.target, restored.target)
                val choices = CaptionLanguages.target(restored, mode)
                assertTrue(choices.accepts("sv"))
                assertTrue(choices.note.contains(integratedCaptionProvider(restored.engine, mode)!!))
                assertFalse(choices.note.contains("Hy-MT"))
            }
        }
    }
    @Test fun choosingCloudWhileInCcDoesNotRestoreOldTranslatorWhenTranslationIsEnabled() {
        val selected = previous.withCaptionMode(CaptionMode.CAPTIONS)
            .selectCaptionEngine(CaptionEngineChoice.CLOUD, SttMode.STREAMING_OPENAI)
        assertEquals(CaptionTranslationRoute.ORIGINAL, captionTranslationRoute(selected, SttMode.STREAMING_OPENAI))
        assertEquals(CaptionTranslationRoute.LIVE_TARGET,
            captionTranslationRoute(selected.withCaptionMode(CaptionMode.TRANSLATE), SttMode.STREAMING_OPENAI))
    }
    @Test fun deliberatelyChoosingTranslatorAfterCloudSelectionStillWorks() {
        val selected = previous.selectCaptionEngine(CaptionEngineChoice.CLOUD, SttMode.STREAMING_OPENAI)
            .copy(textTranslationProviderId = "local").withCaptionMode(CaptionMode.TRANSLATE)
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(selected, SttMode.STREAMING_OPENAI))
        assertTrue(CaptionLanguages.target(selected, SttMode.STREAMING_OPENAI).note.contains("Hy-MT"))
    }
    @Test fun AsrOnlyConnectionsKeepSeparateTranslatorAndDoNotAdvertiseIntegratedTranslation() {
        for (mode in SttMode.entries.filterNot { it in listOf(SttMode.STREAMING_OPENAI, SttMode.STREAMING_SONIOX) }) {
            val selected = previous.selectCaptionEngine(CaptionEngineChoice.CLOUD, mode)
            assertNull(integratedCaptionProvider(selected.engine, mode))
            assertEquals("local", selected.textTranslationProviderId)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(selected, mode))
        }
        assertNull(integratedCaptionProvider(CaptionEngineChoice.NEMOTRON, SttMode.STREAMING_OPENAI))
    }
}
