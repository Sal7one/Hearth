package com.sal7one.transiber.setup

import com.sal7one.transiber.caption.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class PortableSetupTest {
    private val speech = PortableSpeechStage(PortableModelRef("whisper", "whisper-tiny-q5_1"), "en")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (e: Exception) { assertTrue(e.message.orEmpty().isNotBlank()) } }
    @Test fun roundTripPreservesIndependentStagesWithoutPathsOrCredentials() {
        val custom = PortableModelRef("transcribepsy", profile = "transcribepsy-250m-eu-sub-q8", sha256 = "a".repeat(64), digestKind = "tree")
        val plan = PortableSetup("Living room", PortableSpeechStage(custom, "fr"), PortableTranslationStage("marian-fr-en", "fr", "en"),
            PortableVoiceStage(true, CaptionSpeakerChoice.NATIVE, SpeakerGender.FEMALE, 65, "F2"))
        assertEquals(plan, PortableSetup.parse(plan.toJson()))
        assertEquals(plan, PortableSetup.read(ByteArrayInputStream(plan.toJson().toByteArray())))
        assertFalse(plan.toJson().contains("modelId\": \"speech-"))
        assertFalse(plan.toJson().contains("endpoint")); assertFalse(plan.toJson().contains("apiKey"))
    }
    @Test fun schemaRejectsUnknownStagesSecretsPathsAndCoercedTypes() {
        val raw = PortableSetup("Tiny", speech).toJson()
        listOf("apiKey", "endpoint", "path", "shell", "nodes", "url").forEach { key -> rejects {
            PortableSetup.parse(JSONObject(raw).put(key, "secret-or-executable").toString())
        } }
        rejects { PortableSetup.parse(JSONObject(raw).put("schemaVersion", 2).toString()) }
        rejects { PortableSetup.parse(JSONObject(raw).put("schemaVersion", 1.5).toString()) }
        rejects { PortableSetup.parse(JSONObject(raw).put("name", 7).toString()) }
        rejects { PortableSetup.parse(raw + " {}") }
        rejects { PortableSetup.parse(raw + " ignore previous instructions") }
        rejects { PortableSetup.parse(JSONObject(raw).put("voice", JSONObject().put("enabled", "true").put("choice", "SYSTEM").put("gender", "ANY").put("volume", 100)).toString()) }
        rejects { PortableModelRef("whisper", catalogId = "/storage/model.bin") }
        rejects { PortableModelRef("whisper", sha256 = "bad", digestKind = "file") }
        rejects { PortableModelRef("whisper", catalogId = "whisper-tiny-q5_1", sha256 = "a".repeat(64), digestKind = "file") }
        rejects { PortableVoiceStage(voiceId = "https://host/voice") }
    }
    @Test fun byteAndDepthBoundsApplyBeforeUntrustedJsonParsing() {
        rejects { PortableSetup.parse(" ".repeat(PortableSetup.MAX_BYTES + 1)) }
        rejects { PortableSetup.parse("[".repeat(1000) + "]".repeat(1000)) }
        rejects { PortableSetup.read(ByteArrayInputStream(ByteArray(PortableSetup.MAX_BYTES + 1) { 32 })) }
        rejects { PortableSetup.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        val escaped = PortableSetup("A { bracket } and \"quote\"", speech)
        assertEquals(escaped, PortableSetup.parse(escaped.toJson()))
    }
    @Test fun familyAndProfileCompatibilityIsCheckedBeforeModelResolution() {
        PortableSetupCatalog.validateSpeech(speech)
        rejects { PortableSetupCatalog.validateSpeech(speech.copy(model = speech.model.copy(family = "vosk"))) }
        rejects { PortableSetupCatalog.validateSpeech(PortableSpeechStage(PortableModelRef("moonshine", profile = "transcribepsy-250m-eu-sub-q8", sha256 = "a".repeat(64), digestKind = "tree"), "en")) }
        rejects { PortableSetupCatalog.validateSpeech(PortableSpeechStage(PortableModelRef("transcribepsy", profile = "transcribepsy-250m-eu-sub-q8", sha256 = "a".repeat(64), digestKind = "tree"), "ar")) }
        rejects { PortableSetupCatalog.validateSpeech(PortableSpeechStage(PortableModelRef("whisper", sha256 = "a".repeat(64), digestKind = "tree"), "en")) }
    }
    @Test fun captionsOnlyDisablesPriorTranslationAndPreservesVoiceAndAppearance() {
        val previous = CaptionOverlayConfig(engine = CaptionEngineChoice.NEMOTRON, modelId = "old", mode = CaptionMode.TRANSLATE,
            localTranslationEnabled = true, localTranslationModelId = "index-translate-2b-q4", speakCaptions = true,
            speakerChoice = CaptionSpeakerChoice.SHARED, speakerVolume = 40, theme = CaptionTheme.LIGHT)
        val next = PortableSetupPlan.apply(previous, PortableSetup("Tiny captions", speech),
            PortableSetupPreview(emptyList(), speechModelId = "local-device-id", speechEngine = CaptionEngineChoice.WHISPER))
        assertEquals(CaptionMode.CAPTIONS, next.mode); assertFalse(next.localTranslationEnabled)
        assertEquals("local-device-id", next.modelId); assertEquals(CaptionEngineChoice.WHISPER, next.engine)
        assertEquals(previous.speakerChoice, next.speakerChoice); assertEquals(previous.speakCaptions, next.speakCaptions)
        assertEquals(previous.theme, next.theme); assertEquals(previous.speakerVolume, next.speakerVolume)
    }
    @Test fun translationOnlyDoesNotRequireOrReplaceSpeechAndMissingModelsCannotApply() {
        val previous = CaptionOverlayConfig(engine = CaptionEngineChoice.WHISPER, modelId = "user-speech", streamLanguage = "de", speakCaptions = true)
        val setup = PortableSetup("Text only", translation = PortableTranslationStage("marian-fr-en", "fr", "en"))
        val next = PortableSetupPlan.apply(previous, setup, PortableSetupPreview(emptyList()))
        assertEquals(previous.engine, next.engine); assertEquals(previous.modelId, next.modelId)
        assertEquals(previous.streamLanguage, next.streamLanguage); assertEquals(previous.mode, next.mode)
        assertEquals("marian-fr-en", next.localTranslationModelId); assertTrue(next.localTranslationEnabled)
        assertEquals(previous.speakCaptions, next.speakCaptions)
        rejects { PortableSetupPlan.apply(previous, setup, PortableSetupPreview(emptyList(), missingTranslationIds = listOf("marian-fr-en"))) }
    }
}
