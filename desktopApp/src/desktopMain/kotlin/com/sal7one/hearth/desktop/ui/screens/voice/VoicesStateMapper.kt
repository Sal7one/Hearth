package com.sal7one.hearth.desktop.ui.screens.voice

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption

/**
 * Resolves the voices page. `speed` and `steps` are the keys the Supertonic owner
 * reads; the style is the saved `voiceId`, which is the same value the pipeline
 * passes to synthesis.
 */
internal fun voicesState(capabilities: List<EngineDescriptor>, config: PipelineConfig): VoicesState {
    val engines = capabilities.filter { it.stage == PipelineStage.TTS }
    val chosen = config.selection(PipelineStage.TTS)
    val descriptor = engines.firstOrNull { it.id == chosen?.engineId }

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
                detail = when (engine.kind) {
                    EngineKind.LOCAL -> "On this Mac · CPU"
                    EngineKind.SYSTEM -> "Installed OS voices"
                    EngineKind.CLOUD -> "OpenAI-compatible speech"
                },
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
        ready = descriptor?.available == true && (!descriptor.requiresModel || !chosen?.modelPath.isNullOrBlank()),
        engineDetail = descriptor?.name ?: VoicesCopy.OFF,
        placementLabel = if (descriptor == null) "—" else "CPU",
        outputLabel = if (descriptor?.id == "supertonic-local") "44.1 kHz WAV" else "Engine WAV output",
    )
}
