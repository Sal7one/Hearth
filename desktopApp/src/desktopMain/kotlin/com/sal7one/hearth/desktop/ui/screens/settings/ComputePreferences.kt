package com.sal7one.hearth.desktop.ui.screens.settings

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ui.screens.speech.Compute

internal data class ComputeSettings(val selected: Compute, val options: List<Compute>, val issue: String?)

/** A saved GPU choice remains visible when unavailable; the UI never presents a silent CPU fallback. */
internal fun computeSettings(engine: EngineDescriptor?, selection: EngineSelection?): ComputeSettings {
    val key = selection?.options?.get("acceleration") ?: "cpu"
    val selected = Compute.fromKey(key)
    if (engine?.kind != EngineKind.LOCAL) return ComputeSettings(selected ?: Compute.CPU, emptyList(), null)
    val options = if (engine.available) Compute.entries.filter { compute -> engine.acceleration.any { it.equals(compute.key, true) } } else emptyList()
    val issue = when {
        !engine.available -> engine.unavailableReason ?: "${engine.name} is unavailable on this computer."
        selected == null -> "Unknown compute setting '$key'. Choose one of the available options."
        selected !in options -> "${selected.label} is unavailable for this engine on this computer. Choose an available option."
        else -> null
    }
    return ComputeSettings(selected ?: Compute.CPU, options, issue)
}

/** Fill only an absent choice; explicit CPU/GPU choices and independent tuning always win. */
internal fun preferDetectedCompute(engine: EngineDescriptor, selection: EngineSelection): EngineSelection {
    if (selection.engineId != engine.id || !engine.available || engine.kind != EngineKind.LOCAL ||
        engine.stage !in setOf(PipelineStage.STT, PipelineStage.TRANSLATION) || "acceleration" in selection.options) return selection
    val available = computeSettings(engine, selection).options
    val preferred = listOf(Compute.CUDA, Compute.METAL).firstOrNull { it in available } ?: return selection
    return selection.copy(options = selection.options + ("acceleration" to preferred.key))
}
