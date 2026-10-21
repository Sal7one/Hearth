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
                name = when (engine.kind) {
                    EngineKind.LOCAL -> "Supertonic 3"
                    EngineKind.SYSTEM -> "System voice"
                    EngineKind.CLOUD -> engine.name
                },
                detail = when (engine.kind) {
                    EngineKind.LOCAL -> "On this Mac · CPU"
                    EngineKind.SYSTEM -> "Installed OS voices"
                    EngineKind.CLOUD -> "OpenAI-compatible speech"
                },
                note = when {
                    !engine.available -> ""
                    engine.kind == EngineKind.LOCAL -> "Ten styles, Arabic and English."
                    engine.kind == EngineKind.SYSTEM -> "No model needed. Quality depends on the OS."
                    else -> "Needs an endpoint and a key."
                },
                available = engine.available,
                unavailableReason = engine.unavailableReason,
            )
        },
        selectedEngineId = descriptor?.id,
        selectedStyle = config.voiceId.ifBlank { "F1" },
        speed = chosen?.options?.get("speed")?.toFloatOrNull() ?: 1f,
        steps = chosen?.options?.get("steps")?.toIntOrNull() ?: 5,
        readAloud = config.voice != null,
        speakTranslation = config.speakTranslatedText,
        ready = descriptor?.available == true,
        engineDetail = when {
            descriptor == null -> VoicesCopy.OFF
            descriptor.kind == EngineKind.LOCAL -> "Supertonic 3"
            descriptor.kind == EngineKind.SYSTEM -> "System voice"
            else -> descriptor.name
        },
        placementLabel = if (descriptor == null) "—" else "CPU",
    )
}
