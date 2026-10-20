package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.PipelineSnapshot
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.EngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPresentationTest {
    @Test fun transcriptExportNeverPublishesInterimOrInventsMissingTranslation() {
        val state = PipelineSnapshot("run", segments = listOf(
            CaptionSegment("1", "Final original", "الترجمة", final = true),
            CaptionSegment("2", "Still listening", final = false),
            CaptionSegment("3", "Untranslated final", final = true),
        ))
        assertEquals("Final original\nUntranslated final", finalTranscript(state, translated = false))
        assertEquals("الترجمة", finalTranscript(state, translated = true))
    }

    @Test fun overlayKeepsOriginalWhileTranslationIsPendingAndLabelsPartials() {
        val pending = overlayCaption(listOf(CaptionSegment("1", "Hello", final = false)), showOriginal = true)!!
        assertEquals("Hello", pending.text)
        assertTrue(pending.partial)
        assertNull(pending.original)
        val translated = overlayCaption(listOf(CaptionSegment("1", "Hello", "مرحبا")), showOriginal = true)!!
        assertEquals("مرحبا", translated.text)
        assertEquals("Hello", translated.original)
        assertNull(overlayCaption(emptyList(), showOriginal = true))
    }

    @Test fun advancedOptionsPreserveValuesAndRejectAmbiguousEdits() {
        assertEquals(mapOf("promptMode" to "riva", "custom" to "a=b"), parseEngineOptions("promptMode=riva\ncustom=a=b"))
        assertFailsWith<IllegalArgumentException> { parseEngineOptions("threads=2\nthreads=8") }
        assertFailsWith<IllegalArgumentException> { parseEngineOptions("missing separator") }
    }

    @Test fun desktopDataUsesThePlatformDirectoryAndExplicitOverride() {
        assertEquals("/user/Library/Application Support/Hearth", desktopDataDirectory("Mac OS X", "/user", emptyMap()).toString())
        assertEquals("/data/hearth", desktopDataDirectory("Linux", "/user", mapOf("XDG_DATA_HOME" to "/data")).toString())
        assertEquals("/chosen", desktopDataDirectory("Mac OS X", "/user", mapOf("HEARTH_DATA_DIRECTORY" to "/chosen")).toString())
    }

    @Test fun arabicFilterRetainsMultilingualWhisperAndExcludesEnglishOnlyVariants() {
        assertTrue(modelSupportsLanguage(setOf("multilingual"), "ar"))
        assertTrue(modelSupportsLanguage(setOf("ar", "en"), "ar"))
        kotlin.test.assertFalse(modelSupportsLanguage(setOf("en"), "ar"))
        assertEquals("Arabic (ar)", languageLabel("ar"))
    }

    @Test fun translatedOnlyOverlayDoesNotLeakOriginalWhileTranslationIsPending() {
        val pending = listOf(CaptionSegment("1", "Original", final = false))
        assertNull(overlayCaption(pending, showOriginal = true, translationEnabled = true, showOriginalWhilePending = false))
        assertEquals("Original", overlayCaption(pending, showOriginal = false,
            translationEnabled = false, showOriginalWhilePending = false)?.text)
        val translated = listOf(CaptionSegment("1", "Original", "ترجمة"))
        assertEquals(OverlayCaption("ترجمة", null, false), overlayCaption(translated,
            showOriginal = false, translationEnabled = true, showOriginalWhilePending = false))
    }

    @Test fun modelRepairRestoresPublisherOptionsAndKeepsIndependentExecutionSettings() {
        val route = EngineSelection("local-translator", "riva", "/models/riva", options = mapOf(
            "sha256" to "wrong", "promptMode" to "euro", "acceleration" to "metal", "threads" to "4"))
        val verified = route.copy(options = mapOf("sha256" to "pinned", "promptMode" to "riva"))
        kotlin.test.assertFalse(isCurrentModelSelection(route, verified))
        val speech = EngineSelection("speech")
        val config = PipelineConfig(speech = speech, translation = route)
        val repaired = config.useVerifiedModel(PipelineStage.TRANSLATION, verified)
        assertEquals(speech, repaired.speech)
        assertEquals(mapOf("sha256" to "pinned", "promptMode" to "riva", "acceleration" to "metal", "threads" to "4"), repaired.translation?.options)
        assertTrue(isCurrentModelSelection(repaired.translation, verified))
    }

    @Test fun switchingVoiceEnginesClearsIncompatibleVoiceIdButModelChangesRetainIt() {
        val config = PipelineConfig(voice = EngineSelection("supertonic-local", "old"), voiceId = "M2")
        assertEquals("M2", config.select(PipelineStage.TTS, EngineSelection("supertonic-local", "new")).voiceId)
        assertEquals("", config.select(PipelineStage.TTS, EngineSelection("system-tts")).voiceId)
    }

    @Test fun explicitSetupImportReconnectsOnlyMatchingInstalledModelsWithoutLosingTuning() {
        val imported = PipelineConfig(
            speech = EngineSelection("whisper-local", "whisper-tiny", options = mapOf("threads" to "2", "sha256" to "stale")),
            translation = EngineSelection("local-translator", "not-installed"),
            voice = EngineSelection("cloud-tts", "same-id-as-local-voice"),
            sourceLanguage = "ar", targetLanguage = "en", voiceId = "owner-voice",
        )
        val speech = EngineSelection("whisper-local", "whisper-tiny", "/verified/tiny.bin", options = mapOf("sha256" to "publisher-pin"))
        val result = reconnectInstalledModels(imported) { id -> when (id) {
            "whisper-tiny" -> speech
            "same-id-as-local-voice" -> EngineSelection("supertonic-local", id, "/verified/voice")
            else -> null
        } }
        assertEquals(speech.copy(options = mapOf("threads" to "2", "sha256" to "publisher-pin")), result.speech)
        assertEquals(imported.translation, result.translation)
        assertEquals(imported.voice, result.voice)
        assertEquals("owner-voice", result.voiceId)
        assertEquals("ar", result.sourceLanguage)
    }
}
