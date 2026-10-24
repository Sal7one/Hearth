package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.language.*
import com.sal7one.hearth.desktop.ui.screens.translate.translateState
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PipelineLanguagesTest {
    private val engines = listOf(
        EngineDescriptor("whisper-local", "Whisper", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("qwen3-asr-local", "Qwen", PipelineStage.STT, EngineKind.LOCAL, setOf("ar", "en"), requiresModel = true),
        EngineDescriptor("marian-local", "QuickMT", PipelineStage.TRANSLATION, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("llama-translation-local", "Local translation", PipelineStage.TRANSLATION, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("openai-translation", "Provider", PipelineStage.TRANSLATION, EngineKind.CLOUD, requiresCredential = true),
    )
    private fun card(id: String, engine: String, stage: PipelineStage, languages: Set<String>, options: Map<String, String> = emptyMap()) =
        DesktopModelCard(id, id, "test", stage, engine, languages, "https://example.test/model", "test", "", options, emptyList())
    private val forward = card("quick-en-ar", "marian-local", PipelineStage.TRANSLATION, setOf("en", "ar"), mapOf("sources" to "en", "targets" to "ar"))
    private val reverse = forward.copy(id = "quick-ar-en", options = mapOf("sources" to "ar", "targets" to "en"))
    private val whisper = card("whisper", "whisper-local", PipelineStage.STT, setOf("multilingual", "ar", "en"))
    private val euro = card("euro", "llama-translation-local", PipelineStage.TRANSLATION, setOf("ar", "en", "fr"))
    private val catalog = listOf(forward, reverse, whisper, euro)

    @Test fun quickMtNeverOffersAutoOrTheOppositeDirectionAndRepairsOnlyOnExplicitUse() {
        val speech = EngineSelection("whisper-local", "whisper", "/verified/whisper", options = mapOf("threads" to "3"))
        val initial = PipelineConfig(speech = speech, translation = EngineSelection("marian-local", forward.id, "/verified/quick"),
            voice = EngineSelection("system-tts"), sourceLanguage = "auto", targetLanguage = "ar", voiceId = "owner-voice")
        val before = pipelineLanguages(initial, engines, catalog)
        assertEquals(listOf("en"), before.sources.map { it.code })
        assertEquals(listOf("ar"), before.targets.map { it.code })
        assertNotNull(before.issue)
        assertFalse(before.canSwap)
        val normalized = normalizePipelineLanguages(initial, engines, catalog)
        assertEquals(initial.copy(sourceLanguage = "en"), normalized)
        assertNull(pipelineLanguages(normalized, engines, catalog).issue)
        assertEquals(normalized, normalizePipelineLanguages(normalized, engines, catalog))
        val opposite = normalizePipelineLanguages(normalized.copy(translation = EngineSelection("marian-local", reverse.id)), engines, catalog)
        assertEquals("ar", opposite.sourceLanguage)
        assertEquals("en", opposite.targetLanguage)
        assertEquals(speech, opposite.speech)
        assertEquals(initial.voice, opposite.voice)
        assertEquals("owner-voice", opposite.voiceId)
    }

    @Test fun typedSourceIsAlwaysExplicitWhileWhisperCanDetectForLiveMultilingualTranslation() {
        val config = PipelineConfig(speech = EngineSelection("whisper-local", "whisper"),
            translation = EngineSelection("llama-translation-local", "euro"), sourceLanguage = "auto", targetLanguage = "ar")
        val live = pipelineLanguages(config, engines, catalog)
        assertTrue(live.sources.any { it.code == "auto" })
        assertNull(live.issue)
        val typed = pipelineLanguages(config, engines, catalog, typedText = true)
        assertFalse(typed.sources.any { it.code == "auto" })
        assertNotNull(typed.issue)
        assertFalse(typed.targets.any { it.code == "auto" })
        assertFalse(pipelineLanguages(config.copy(speech = EngineSelection("qwen3-asr-local")), engines, catalog).sources.any { it.code == "auto" })
    }

    @Test fun multilingualPhononOffersAutoOnlyForOriginalCaptionsAndKeepsCheckpointCoverage() {
        val phonon = EngineDescriptor("phonon-local", "Phonon", PipelineStage.STT, EngineKind.LOCAL,
            setOf("en", "ru", "fr"), requiresModel = true)
        val multilingual = card("phonon-2.5", "phonon-local", PipelineStage.STT, setOf("en", "ru", "fr"))
        val english = multilingual.copy(id = "phonon-2", languages = setOf("en"))
        val models = catalog + multilingual + english
        val allEngines = engines + phonon
        val cc = PipelineConfig(speech = EngineSelection("phonon-local", "phonon-2.5"), sourceLanguage = "auto", targetLanguage = "ar")
        val automatic = pipelineLanguages(cc, allEngines, models)
        assertNull(automatic.issue)
        assertEquals("Auto · original captions", automatic.sources.single { it.code == "auto" }.label)
        assertEquals(setOf("auto", "en", "ru", "fr"), automatic.sources.map { it.code }.toSet())
        assertFalse(automatic.canSwap)
        val translated = cc.copy(translation = EngineSelection("openai-translation"))
        val unknownSource = pipelineLanguages(translated, allEngines, models)
        assertTrue(unknownSource.issue!!.contains("does not report"))
        assertFalse(unknownSource.sources.any { it.code == "auto" })
        assertNull(pipelineLanguages(translated.copy(sourceLanguage = "ru"), allEngines, models).issue)
        val old = cc.copy(speech = EngineSelection("phonon-local", "phonon-2"), sourceLanguage = "ru")
        assertEquals(setOf("en"), pipelineLanguages(old, allEngines, models).sources.map { it.code }.toSet())
        assertNotNull(pipelineLanguages(old, allEngines, models).issue)
    }

    @Test fun checkpointLanguageAndRivaEnglishPairConstraintsAreAppliedToSwapping() {
        val english = whisper.copy(id = "whisper-en", languages = setOf("en"))
        val restricted = pipelineLanguages(PipelineConfig(speech = EngineSelection("whisper-local", english.id)), engines, catalog + english)
        assertEquals(setOf("en"), restricted.sources.map { it.code }.toSet())
        val riva = euro.copy(id = "riva", options = mapOf("family" to "riva-translate"))
        val config = PipelineConfig(speech = EngineSelection("whisper-local", "whisper"),
            translation = EngineSelection("llama-translation-local", "riva"), sourceLanguage = "en", targetLanguage = "ar")
        assertTrue(pipelineLanguages(config, engines, catalog + riva).canSwap)
        val swapped = pipelineLanguages(config.copy(sourceLanguage = "ar", targetLanguage = "en"), engines, catalog + riva)
        assertNull(swapped.issue)
        assertEquals(listOf("en"), swapped.targets.map { it.code })
        assertNotNull(pipelineLanguages(config.copy(sourceLanguage = "ar", targetLanguage = "fr"), engines, catalog + riva).issue)
    }

    @Test fun verifiedReadinessRequiresMatchingEngineAndExactVerifiedPath() {
        val route = EngineSelection("marian-local", forward.id, "/verified/model")
        val config = PipelineConfig(translation = route)
        val installed = mapOf(forward.id to DesktopModelStatus(true, Path.of("/verified/model")))
        assertNull(stageSetupIssue(config, PipelineStage.TRANSLATION, engines, installed, catalog))
        assertNotNull(stageSetupIssue(config.copy(translation = route.copy(modelPath = "/other/model")), PipelineStage.TRANSLATION, engines, installed, catalog))
        assertNotNull(stageSetupIssue(config.copy(translation = route.copy(engineId = "llama-translation-local")), PipelineStage.TRANSLATION, engines, installed, catalog))
        assertNotNull(stageSetupIssue(config, PipelineStage.TRANSLATION, engines, emptyMap(), catalog))
    }

    @Test fun localhostProviderCanBeConfiguredWithoutAKeyButRemoteProviderCannot() {
        val route = EngineSelection("openai-translation", endpoint = "http://127.0.0.1:8080/v1")
        assertNull(stageSetupIssue(PipelineConfig(translation = route), PipelineStage.TRANSLATION, engines, emptyMap(), catalog))
        assertNotNull(stageSetupIssue(PipelineConfig(translation = route.copy(endpoint = "https://provider.test/v1")), PipelineStage.TRANSLATION, engines, emptyMap(), catalog))
    }

    @Test fun failedOrDisabledTranslationNeverTurnsOriginalIntoTranslatedOutput() {
        val failed = DesktopUiState(input = WorkspaceInput.TEXT, snapshot = PipelineSnapshot("failed", PipelineStatus.FAILED,
            segments = listOf(CaptionSegment("1", "This is the original", final = true)), error = "Provider rejected the request"))
        val state = translateState(PipelineConfig(sourceLanguage = "en", targetLanguage = "ar"), failed, engines, catalog)
        assertFalse(state.hasResult)
        assertEquals("", state.translated)
        assertEquals("This is the original", state.original)
        assertEquals("Provider rejected the request", state.error)
        assertFalse(state.ready)
    }

    @Test fun typedResultsKeepTheirActualTargetAndNeverShowMicrophoneOrFileResults() {
        val completed = PipelineSnapshot("text", PipelineStatus.COMPLETED,
            segments = listOf(CaptionSegment("1", "Hello", "مرحبا", final = true)))
        val previous = PipelineConfig(sourceLanguage = "en", targetLanguage = "ar")
        val next = previous.copy(targetLanguage = "fr")
        val workspace = DesktopUiState(input = WorkspaceInput.TEXT, sessionConfig = previous, snapshot = completed)
        val result = translateState(next, workspace, engines, catalog)
        assertTrue(result.hasResult)
        assertEquals("مرحبا", result.translated)
        assertEquals("Arabic", result.resultTargetLabel)
        assertEquals("French", result.targetLabel)
        for (input in listOf(WorkspaceInput.MICROPHONE, WorkspaceInput.AUDIO_FILE)) {
            assertFalse(translateState(next, workspace.copy(input = input), engines, catalog).hasResult)
        }
    }

    @Test fun systemAudioAndMicrophoneBothExposeNormalFinishSemantics() {
        assertTrue(CaptionsState(systemAudio = true, active = true).liveCapture)
        assertTrue(CaptionsState(microphone = true, active = true).liveCapture)
        assertFalse(CaptionsState(inputLabel = "Audio file", active = true).liveCapture)
    }

    @Test fun whisperPickerCodesMatchThePinnedNativeLanguageTable() {
        val relative = "common-jni/src/main/cpp/whisper/upstream/src/whisper.cpp"
        val source = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve(relative) }.first { Files.isRegularFile(it) }
        val table = Files.readString(source).substringAfter("g_lang = {").substringBefore("\n};")
        val codes = Regex("\\{\\s*\"([^\"]+)\"\\s*,").findAll(table).map { it.groupValues[1] }.toSet()
        assertEquals(codes, whisperLanguageCodes)
    }
}
