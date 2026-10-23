package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ui.screens.settings.preferDetectedCompute

/**
 * Small, tested-by-use helpers for moving a stage selection through
 * [PipelineConfig]. They lived in the deleted first UI file; the new screens and
 * the application root share them unchanged.
 */
internal fun PipelineConfig.selection(stage: PipelineStage): EngineSelection? = when (stage) {
    PipelineStage.STT -> speech
    PipelineStage.TRANSLATION -> translation
    PipelineStage.TTS -> voice
}

internal fun PipelineConfig.select(stage: PipelineStage, selection: EngineSelection?): PipelineConfig = when (stage) {
    PipelineStage.STT -> copy(speech = selection)
    PipelineStage.TRANSLATION -> copy(translation = selection)
    PipelineStage.TTS -> copy(voice = selection, voiceId = if (voice?.engineId == selection?.engineId) voiceId else "")
}

/** Applying a verified model keeps the options the user already set for that engine. */
internal fun PipelineConfig.useVerifiedModel(stage: PipelineStage, verified: EngineSelection): PipelineConfig {
    val previous = selection(stage)?.takeIf { it.engineId == verified.engineId }
    return select(stage, verified.copy(options = previous?.options.orEmpty() + verified.options))
}

internal fun engineSelection(engine: EngineDescriptor) = EngineSelection(
    engine.id,
    modelId = engine.defaultModel,
    endpoint = engine.defaultEndpoint,
    credentialId = if (engine.requiresCredential) "${engine.stage.name.lowercase()}-${engine.id}" else "",
)

/** Clicking the current engine must preserve its verified model, credentials and tuning. */
internal fun PipelineConfig.chooseEngine(engine: EngineDescriptor, remembered: PipelineConfig? = null,
    installed: EngineSelection? = null): PipelineConfig {
    if (selection(engine.stage)?.engineId == engine.id) return this
    val previous = remembered?.selection(engine.stage)?.takeIf { it.engineId == engine.id }
    val route = previous ?: preferDetectedCompute(engine,
        installed?.takeIf { it.engineId == engine.id } ?: engineSelection(engine))
    val next = select(engine.stage, route)
    return if (engine.stage == PipelineStage.TTS && previous != null) next.copy(voiceId = remembered.voiceId,
        speakTranslatedText = remembered.speakTranslatedText) else next
}

internal fun PipelineConfig.enableReadAloud(engines: List<EngineDescriptor>,
    remembered: (String) -> PipelineConfig? = { null }, installed: (String) -> EngineSelection? = { null }): PipelineConfig {
    if (voice != null) return this
    val available = engines.filter { it.stage == PipelineStage.TTS && it.available }
    val preferred = available.firstOrNull { it.id == "supertonic-local" } ?: available.firstOrNull()
    check(preferred != null) { "No voice engine is available. Configure a voice engine before enabling read aloud." }
    return chooseEngine(preferred, remembered(preferred.id), installed(preferred.id))
}
