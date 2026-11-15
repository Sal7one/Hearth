package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelArtifact
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.screens.home.homeState
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
import com.sal7one.hearth.desktop.ui.screens.presets.BuiltInPresets
import com.sal7one.hearth.desktop.ui.screens.presets.applyDesktopPreset
import com.sal7one.hearth.desktop.ui.screens.presets.presetsState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPresetsTest {
    private val moonshine = card("moonshine-tiny-en-v2", "moonshine-local", PipelineStage.STT, setOf("en"), 30)
    private val phonon = card("phonon-2.5", "phonon-local", PipelineStage.STT, setOf("en", "ru"), 339)
    private val quickMt = card("ct2-quickmt-en-ar", "marian-local", PipelineStage.TRANSLATION, setOf("en", "ar"), 363,
        mapOf("sources" to "en", "targets" to "ar"))
    private val voice = card("supertonic-3", "supertonic-local", PipelineStage.TTS, setOf("en", "ar"), 401)
    private val whisper = card("whisper-base-q5_1", "whisper-local", PipelineStage.STT, setOf("multilingual"), 60)

    @Test fun starterOnlyDownloadsUnverifiedStagesAndNeverQueuesAnActiveCheck() {
        val catalog = listOf(moonshine, quickMt, voice)
        val installed = mapOf(moonshine.id to DesktopModelStatus(true, Path.of("/models/moonshine")))
        val modelCheck = ModelTransfer(quickMt.id, importing = true)
        val state = presetsState(BuiltInPresets.all, catalog, installed, PipelineConfig(),
            transfers = mapOf(quickMt.id to modelCheck))
        assertEquals("english-captions", state.starter?.id)
        val voicePreset = state.presets.single { it.id == "arabic-readback" }
        assertEquals("Preparing", voicePreset.statusLabel)
        assertEquals(listOf(voice.id), voicePreset.downloadIds)
        assertEquals(401L, voicePreset.downloadBytes)
        assertEquals(794L, voicePreset.totalBytes)
        assertTrue(voicePreset.measurements.any { it.modelId == moonshine.id })
    }

    @Test fun usesDirectionalLanguageAndVoiceAndKeepsTuningForTheSameCheckpoint() {
        val definition = BuiltInPresets.all.single { it.id == "arabic-readback" }
        val catalog = listOf(moonshine, quickMt, voice)
        val verified = catalog.associate { it.id to EngineSelection(it.engineId, it.id, "/models/${it.id}", options = it.options) }
        val current = PipelineConfig(
            speech = verified.getValue(moonshine.id).copy(options = mapOf("threads" to "3")),
            sourceLanguage = "ru", targetLanguage = "en", speakTranslatedText = false,
        )
        assertFailsWith<IllegalArgumentException> {
            applyDesktopPreset(current, definition, catalog.map(::descriptor), catalog) {
                verified[it].takeUnless { route -> route?.modelId == voice.id }
            }
        }
        assertNull(current.translation)
        val result = applyDesktopPreset(current, definition, catalog.map(::descriptor), catalog, verified::get)
        assertEquals("en", result.sourceLanguage)
        assertEquals("ar", result.targetLanguage)
        assertEquals("3", result.speech?.options?.get("threads"))
        assertEquals(quickMt.id, result.translation?.modelId)
        assertEquals(voice.id, result.voice?.modelId)
        assertEquals("F1", result.voiceId)
        assertTrue(result.speakTranslatedText)
    }

    @Test fun savedPresetOnlyShowsActiveWhenExactCopyAndDirectionMatch() {
        val catalog = listOf(moonshine, quickMt)
        val speech = EngineSelection(moonshine.engineId, moonshine.id, Path.of("/verified/speech").toString())
        val translation = EngineSelection(quickMt.engineId, quickMt.id, Path.of("/verified/translation").toString())
        val verified = listOf(speech, translation).associate { it.modelId to DesktopModelStatus(true, Path.of(it.modelPath)) }
        val saved = PipelineConfig(speech = speech, translation = translation, sourceLanguage = "en", targetLanguage = "ar")
        fun view(config: PipelineConfig) =
            presetsState(BuiltInPresets.all, catalog, verified, config).presets.single { it.id == "english-to-arabic" }
        assertTrue(view(saved).active)
        assertFalse(view(saved.copy(targetLanguage = "en")).active)
        assertFalse(view(saved.copy(translation = translation.copy(modelPath = "/missing/copy"))).active)
    }

    @Test fun desktopWithoutCompactRuntimeCanStillChooseItsCompatibleFallback() {
        val catalog = listOf(moonshine, whisper)
        val engines = listOf(descriptor(moonshine).copy(available = false, unavailableReason = "Speech runtime missing"),
            descriptor(whisper))
        val state = presetsState(BuiltInPresets.all, catalog, emptyMap(), PipelineConfig(), engines)
        assertEquals("portable-captions", state.starter?.id)
        assertEquals("Speech runtime missing", state.presets.single { it.id == "english-captions" }.unavailableReason)
        assertEquals(listOf("portable-captions"),
            presetsState(BuiltInPresets.all, listOf(whisper), emptyMap(), PipelineConfig()).presets.map { it.id })
    }

    @Test fun measuredModelFilterMatchesOnlyExactCatalogIds() {
        val differentCheckpoint = moonshine.copy(id = "moonshine-other", name = moonshine.name)
        val view = modelsState(
            listOf(differentCheckpoint, phonon, moonshine),
            emptyMap(), PipelineConfig(), emptyList(), ModelsFilter(measuredOnly = true), 0,
        )
        val rows = view.groups.flatMap { it.rows }
        assertEquals(setOf(moonshine.id, phonon.id), rows.map { it.id }.toSet())
        assertTrue(rows.all { row -> row.measurements.all { it.modelId == row.id } })
    }

    @Test fun savedSpeechCheckingStateKeepsTheExistingStartupChoice() {
        val route = EngineSelection(moonshine.engineId, moonshine.id, "/saved/moonshine")
        val home = homeState(PipelineConfig(speech = route, sourceLanguage = "en"), listOf(descriptor(moonshine)),
            0, 0, "Ready", false, listOf(moonshine), verifyingSelections = mapOf(moonshine.id to route.modelPath))
        assertTrue(home.hasSavedSpeech)
        assertTrue(home.setupVerifying)
        assertNull(home.setupIssue)
        assertEquals("Checking saved setup", home.statusText)
    }

    private fun card(id: String, engine: String, stage: PipelineStage, languages: Set<String>, bytes: Long,
        options: Map<String, String> = emptyMap()) = DesktopModelCard(
        id, id, engine, stage, engine, languages, "", "", "", options,
        listOf(DesktopModelArtifact("model.bin", "", bytes, "")),
    )

    private fun descriptor(card: DesktopModelCard) =
        EngineDescriptor(card.engineId, card.name, requireNotNull(card.stage), EngineKind.LOCAL, card.languages, requiresModel = true)
}
