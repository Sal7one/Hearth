package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import org.junit.Assert.*
import org.junit.Test

class CaptionLanguagesTest {
    @Test fun nemoPickerOnlyOffersNativeHintsAndAuto() {
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON)
        val choices = CaptionLanguages.source(config, SttMode.BATCH)
        assertEquals(SpeechProfile.NEMOTRON_3_5_ASR_0_6B.capabilities.sourceLanguageHints + "auto", choices.codes)
        assertTrue("ru" in choices.codes && "ar" in choices.codes)
        assertFalse("ur" in choices.codes)
        assertEquals("ru", CaptionLanguages.effectiveSource(config.copy(streamLanguage = "ru-RU"), SttMode.BATCH))
        assertEquals("auto", CaptionLanguages.effectiveSource(config.copy(streamLanguage = "ur"), SttMode.BATCH))
    }
    @Test fun qwenPinsRealLanguagesAndAutomaticCloudPathsHaveNoPicker() {
        val qwen = CaptionOverlayConfig(engine = CaptionEngineChoice.QWEN, streamLanguage = "ru")
        assertEquals(SpeechProfile.QWEN3_ASR_0_6B.capabilities.sourceLanguageHints + "auto", CaptionLanguages.source(qwen, SttMode.BATCH).codes)
        assertEquals("ru", CaptionLanguages.effectiveSource(qwen, SttMode.BATCH))
        assertTrue(CaptionLanguages.source(qwen, SttMode.BATCH).languages.all { it.canForce })
        assertEquals(setOf("model"), CaptionLanguages.source(qwen.copy(engine = CaptionEngineChoice.VOSK), SttMode.BATCH).codes)
        val cloud = qwen.copy(engine = CaptionEngineChoice.CLOUD, mode = CaptionMode.TRANSLATE)
        assertEquals("auto", CaptionLanguages.effectiveSource(cloud, SttMode.STREAMING_OPENAI))
        assertEquals("auto", CaptionLanguages.effectiveSource(cloud, SttMode.BATCH))
        assertEquals("ru", CaptionLanguages.effectiveSource(cloud.copy(mode = CaptionMode.CAPTIONS), SttMode.STREAMING_OPENAI))
        assertEquals("ru", CaptionLanguages.effectiveSource(cloud, SttMode.STREAMING_DEEPGRAM))
    }
    @Test fun sourcePolicyUsesActualWeightsAndKnownCloudModelNotGlobalNames() {
        val whisper = CaptionOverlayConfig(engine = CaptionEngineChoice.WHISPER, streamLanguage = "ru")
        val english = CaptionLanguageModel("renamed", "Renamed weights", 51864)
        assertEquals(setOf("en"), CaptionLanguages.source(whisper, SttMode.BATCH, english).codes)
        assertFalse(CaptionLanguages.source(whisper, SttMode.BATCH, english).allowsSelection)
        assertEquals("en", CaptionLanguages.effectiveSource(whisper, SttMode.BATCH, english))
        assertFalse("yue" in CaptionLanguages.source(whisper, SttMode.BATCH, english.copy(whisperVocabulary = 51865)).codes)
        assertTrue("yue" in CaptionLanguages.source(whisper, SttMode.BATCH, english.copy(whisperVocabulary = 51866)).codes)
        assertEquals(setOf("auto"), CaptionLanguages.source(whisper, SttMode.BATCH).codes)
        val cloud = whisper.copy(engine = CaptionEngineChoice.CLOUD)
        assertEquals(setOf("auto"), CaptionLanguages.source(cloud, SttMode.BATCH, CaptionLanguageModel("my-custom-stt")).codes)
        assertEquals("auto", CaptionLanguages.effectiveSource(cloud, SttMode.BATCH, CaptionLanguageModel("my-custom-stt")))
        assertEquals("ru", CaptionLanguages.effectiveSource(cloud, SttMode.BATCH, CaptionLanguageModel("gpt-4o-mini-transcribe")))
        assertFalse(CaptionLanguages.source(cloud.copy(mode = CaptionMode.TRANSLATE), SttMode.STREAMING_OPENAI).allowsSelection)
    }
    @Test fun headerCoverageDoesNotGuessFromFilenameOrTruncatedData() {
        fun header(vocab: Int) = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x67676d6c).putInt(vocab).array()
        for (vocab in 51864..51866) assertEquals(vocab, whisperVocabulary(header(vocab).inputStream()))
        assertNull(whisperVocabulary(header(1234).inputStream()))
        assertNull(whisperVocabulary(header(51864).take(7).toByteArray().inputStream()))
        assertNull(whisperVocabulary(ByteArray(8).inputStream()))
    }
    @Test fun localTargetsFollowSelectedModelAndPersistBeyondOriginalThree() {
        TranslationCatalog.models.forEach { spec ->
            val config = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON, localTranslationEnabled = true,
                localTranslationModelId = spec.id, mode = CaptionMode.TRANSLATE, target = TranslationTarget.of("ja"))
            assertEquals(spec.targetLanguages, CaptionLanguages.target(config, SttMode.BATCH).codes)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(config, SttMode.BATCH))
            val prefs = mutablePreferencesOf()
            CaptionConfigStore.writeInto(prefs, config)
            assertEquals(config.target, CaptionConfigStore.readFrom(prefs).target)
        }
        for (old in listOf("ENGLISH", "ARABIC", "CHINESE")) {
            val prefs = mutablePreferencesOf(stringPreferencesKey("translation_target") to old)
            assertEquals(TranslationTarget.fromStored(old), CaptionConfigStore.readFrom(prefs).target)
        }
        assertTrue(TranslationTarget.of("ur").rtl)
        assertFalse(TranslationTarget.of("ja").rtl)
        assertThrows(IllegalArgumentException::class.java) { TranslationTarget.of("auto") }
    }
    @Test fun pickerExpansionIsTemporaryAndClamped() {
        val reading = CaptionOverlayConfig(bubbleHeightDp = 160)
        val picker = reading.copy(showSettings = true, languagePicker = CaptionLanguagePicker.SOURCE)
        assertEquals(880, overlayHeightPx(1600, 2f, picker))
        assertEquals(320, overlayHeightPx(1600, 2f, picker.copy(languagePicker = null)))
        assertEquals(240, overlayHeightPx(240, 2f, picker))
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(prefs, picker)
        assertNull(CaptionConfigStore.readFrom(prefs).languagePicker)
        assertEquals(160, CaptionConfigStore.readFrom(prefs).bubbleHeightDp)
    }
}
