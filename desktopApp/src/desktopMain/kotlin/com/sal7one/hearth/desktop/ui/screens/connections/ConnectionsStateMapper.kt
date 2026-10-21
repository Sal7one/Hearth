package com.sal7one.hearth.desktop.ui.screens.connections

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.selection

/**
 * Resolves cloud engines into connection rows. Only the engine currently saved for
 * a stage is marked selected; the rest are offered but not editable until chosen.
 */
internal fun connectionsState(
    capabilities: List<EngineDescriptor>,
    config: PipelineConfig,
    credentialStorage: String,
): ConnectionsState {
    val cloud = capabilities.filter { it.kind == EngineKind.CLOUD }
    val rows = cloud.map { engine ->
        val chosen = config.selection(engine.stage)
        val selected = chosen?.engineId == engine.id
        ConnectionRow(
            engineId = engine.id,
            name = engine.name,
            stage = engine.stage,
            stageLabel = when (engine.stage) {
                PipelineStage.STT -> "Speech"
                PipelineStage.TRANSLATION -> "Translation"
                PipelineStage.TTS -> "Voice"
            },
            selected = selected,
            endpoint = if (selected) chosen?.endpoint.orEmpty() else engine.defaultEndpoint,
            model = if (selected) chosen?.modelId.orEmpty() else engine.defaultModel,
            credentialId = if (selected) chosen?.credentialId.orEmpty() else "",
            available = engine.available,
            unavailableReason = engine.unavailableReason,
        )
    }
    return ConnectionsState(rows = rows, credentialStorage = credentialStorage)
}
