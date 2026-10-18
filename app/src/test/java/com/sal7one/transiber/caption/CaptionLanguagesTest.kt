package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import com.sal7one.transiber.translation.MarianPackage
import org.junit.Assert.*
import org.junit.Test

class CaptionLanguagesTest {
    @Test fun transcribepsyRequiresKnownSourceForTranslationAndDoesNotInventLanguageHints() {
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.TRANSCRIBEPSY, streamLanguage = "auto")
        val choices = CaptionLanguages.source(config, SttMode.BATCH)
        assertEquals(setOf("auto", "en", "nl", "fr", "de", "it", "pl", "pt", "es"), choices.codes)
        assertTrue(choices.languages.none { it.canForce })
        assertEquals("auto", CaptionLanguages.effectiveSource(config, SttMode.BATCH))
        assertEquals("fr", CaptionLanguages.effectiveSource(config.copy(streamLanguage = "fr-FR"), SttMode.BATCH))
        assertEquals("auto", CaptionLanguages.effectiveSource(config.copy(streamLanguage = "ar"), SttMode.BATCH))
        val translated = config.copy(mode = CaptionMode.TRANSLATE, localTranslationEnabled = true, localTranslationModelId = "index-translate-2b-q4")
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(translated, SttMode.BATCH))
        assertEquals(SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB.backend, CaptionEngineChoice.TRANSCRIBEPSY.speechBackend)
    }
    @Test fun moonshineUsesTheVerifiedProfilesFixedLanguageRatherThanAnOldHint() {
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.MOONSHINE, streamLanguage = "en")
        SpeechProfile.entries.filter { it.backend == com.sal7one.common_jni.speech.SpeechBackend.MOONSHINE }.forEach { profile ->
            val model = CaptionLanguageModel(profile.id, profile.id)
            val choices = CaptionLanguages.source(config, SttMode.BATCH, model)
            assertEquals(setOf(profile.fixedSourceLanguage), choices.codes)
            assertFalse(choices.allowsSelection)
            assertFalse(choices.languages.single().canForce)
            assertEquals(profile.fixedSourceLanguage, CaptionLanguages.effectiveSource(config, SttMode.BATCH, model))
        }
        assertEquals(setOf("auto"), CaptionLanguages.source(config, SttMode.BATCH, CaptionLanguageModel("unknown")).codes)
    }

    @Test fun pinnedVoskLanguageRoutesTranslationWithoutGuessingUnrecognizedImports() {
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.VOSK, streamLanguage = "en")
        com.sal7one.transiber.models.VoskDownloads.packages.forEach { source ->
            val model = CaptionLanguageModel("private-install", "Renamed model", fixedSourceLanguage = source.language)
            val choices = CaptionLanguages.source(config, SttMode.BATCH, model)
            assertEquals(setOf(source.language), choices.codes)
            assertFalse(choices.allowsSelection)
            assertEquals(source.language, CaptionLanguages.effectiveSource(config, SttMode.BATCH, model))
            assertEquals(source, com.sal7one.transiber.models.VoskDownloads.findByTreeSha256(source.treeSha256))
        }
        val unknown = CaptionLanguageModel("user-import", "Arabic in its name proves nothing")
        assertEquals(setOf("model"), CaptionLanguages.source(config, SttMode.BATCH, unknown).codes)
        assertEquals("auto", CaptionLanguages.effectiveSource(config, SttMode.BATCH, unknown))
    }

    @Test fun fixedArabicSourceControlsTranslationTargetsDespiteAnOldEnglishHint() {
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.MOONSHINE, streamLanguage = "en",
            localTranslationEnabled = true, textTranslationProviderId = "local", localTranslationModelId = "marian-ar-en")
        val model = CaptionLanguageModel(SpeechProfile.MOONSHINE_BASE_AR.id)
        assertEquals(setOf("en"), CaptionLanguages.target(config, SttMode.BATCH, model = model).codes)
        assertTrue(CaptionLanguages.target(config.copy(localTranslationModelId = "marian-en-fr"), SttMode.BATCH, model = model).codes.isEmpty())
        assertTrue(CaptionLanguages.target(config.copy(textTranslationProviderId = "", localTranslationModelId = "marian-en-fr"), SttMode.BATCH, model = model).codes.isEmpty())
        assertEquals(setOf("en"), CaptionLanguages.target(config.copy(textTranslationProviderId = ""), SttMode.BATCH, model = model).codes)
        assertEquals(setOf("fr"), CaptionLanguages.target(config.copy(localTranslationModelId = "marian-ar-fr-via-en"), SttMode.BATCH, model = model).codes)
        val vosk = CaptionLanguageModel("verified-vosk-install", fixedSourceLanguage = "ar")
        assertEquals(setOf("en"), CaptionLanguages.target(config.copy(engine = CaptionEngineChoice.VOSK), SttMode.BATCH, model = vosk).codes)
    }

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
        MarianPackage.pairs.forEach { pair ->
            val config = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON,
                localTranslationEnabled = true, localTranslationModelId = pair.id,
                mode = CaptionMode.TRANSLATE, streamLanguage = pair.source,
                target = TranslationTarget.of(pair.target))
            assertEquals(setOf(pair.target), CaptionLanguages.target(config, SttMode.BATCH).codes)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR, captionTranslationRoute(config, SttMode.BATCH))
        }
        for (old in listOf("ENGLISH", "ARABIC", "CHINESE")) {
            val prefs = mutablePreferencesOf(stringPreferencesKey("translation_target") to old)
            assertEquals(TranslationTarget.fromStored(old), CaptionConfigStore.readFrom(prefs).target)
        }
        assertTrue(TranslationTarget.of("ur").rtl)
        assertFalse(TranslationTarget.of("ja").rtl)
        assertThrows(IllegalArgumentException::class.java) { TranslationTarget.of("auto") }
    }
    @Test fun pickerKeepsTheSelectedViewportAndIsNotPersisted() {
        val reading = CaptionOverlayConfig(bubbleHeightDp = 160)
        val picker = reading.copy(showSettings = true, languagePicker = CaptionLanguagePicker.SOURCE)
        assertEquals(320, overlayHeightPx(1600, 2f, picker))
        assertEquals(320, overlayHeightPx(1600, 2f, picker.copy(languagePicker = null)))
        assertEquals(320, overlayHeightPx(1600, 2f,
            picker.copy(showSettings = false, languagePicker = null)))
        assertEquals(240, overlayHeightPx(240, 2f, picker))
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(prefs, picker)
        assertNull(CaptionConfigStore.readFrom(prefs).languagePicker)
        assertEquals(160, CaptionConfigStore.readFrom(prefs).bubbleHeightDp)
    }
}
