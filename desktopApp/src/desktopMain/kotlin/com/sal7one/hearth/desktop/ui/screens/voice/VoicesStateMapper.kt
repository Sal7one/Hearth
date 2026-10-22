package com.sal7one.hearth.desktop.ui.screens.voice

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.connectionConfigured
import com.sal7one.hearth.desktop.ui.screens.settings.engineRuntimeLabel
import com.sal7one.hearth.desktop.ui.screens.settings.verifiedSelectionMatches
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus

/**
 * Resolves the voices page. `speed` and `steps` are the keys the Supertonic owner
 * reads; the style is the saved `voiceId`, which is the same value the pipeline
 * passes to synthesis.
 */
internal fun voicesState(capabilities: List<EngineDescriptor>, config: PipelineConfig,
    catalog: List<DesktopModelCard>? = null, installations: Map<String, DesktopModelStatus> = emptyMap()): VoicesState {
    val engines = capabilities.filter { it.stage == PipelineStage.TTS }
    val chosen = config.selection(PipelineStage.TTS)
    val descriptor = engines.firstOrNull { it.id == chosen?.engineId }
    val modelCard = catalog?.firstOrNull { it.id == chosen?.modelId && it.engineId == descriptor?.id && it.stage == PipelineStage.TTS }
    val verified = if (catalog == null) !chosen?.modelPath.isNullOrBlank()
        else verifiedSelectionMatches(chosen, modelCard, modelCard?.let { installations[it.id] })
    val ready = descriptor?.available == true && when {
        descriptor.requiresModel -> verified
        descriptor.kind == EngineKind.CLOUD -> connectionConfigured(descriptor, chosen)
        else -> true
    }

    return VoicesState(
        engines = engines.map { engine ->
            ChoiceOption(
                id = engine.id,
                badge = when (engine.kind) {
                    EngineKind.LOCAL -> "L"
                    EngineKind.SYSTEM -> "S"
                    EngineKind.CLOUD -> "C"
                },
                name = engine.name,
                detail = engineRuntimeLabel(engine),
                note = when {
                    !engine.available -> ""
                    engine.id == "supertonic-local" -> "Ten styles, Arabic and English."
                    engine.kind == EngineKind.LOCAL -> "Uses this engine's downloaded model and voice."
                    engine.kind == EngineKind.SYSTEM -> "No model needed. Quality depends on the OS."
                    else -> "Needs an endpoint and a key."
                },
                available = engine.available,
                unavailableReason = engine.unavailableReason,
            )
        },
        selectedEngineId = descriptor?.id,
        styles = if (descriptor?.id == "supertonic-local") VOICE_STYLES else emptyList(),
        selectedStyle = config.voiceId.ifBlank { if (descriptor?.id == "supertonic-local") "F1" else "Engine default" },
        speed = chosen?.options?.get("speed")?.toFloatOrNull() ?: 1f,
        steps = chosen?.options?.get("steps")?.toIntOrNull() ?: 5,
        readAloud = config.voice != null,
        speakTranslation = config.speakTranslatedText,
        ready = ready,
        engineDetail = descriptor?.name ?: VoicesCopy.OFF,
        placementLabel = when (descriptor?.kind) { EngineKind.LOCAL -> "CPU"; EngineKind.CLOUD -> "Provider"; EngineKind.SYSTEM -> "Operating system"; null -> "—" },
        outputLabel = if (descriptor?.id == "supertonic-local") "44.1 kHz WAV" else "Engine WAV output",
        requiresModel = descriptor?.requiresModel == true,
        modelDetail = modelCard?.name ?: "Choose a verified model",
        modelVerified = verified,
        statusLabel = when {
            descriptor == null -> "Off"
            !descriptor.available -> "Unavailable"
            descriptor.requiresModel && !verified -> "Choose a model"
            descriptor.kind == EngineKind.CLOUD -> if (ready) "Connection configured" else "Configure connection"
            else -> "Ready"
        },
        preparationNote = if (descriptor?.id == "supertonic-local")
            "The resident Supertonic engine is reused across lines; switching style keeps its model loaded."
            else "First-use preparation and memory use vary by engine. Try a short line before live captions.",
    )
}
