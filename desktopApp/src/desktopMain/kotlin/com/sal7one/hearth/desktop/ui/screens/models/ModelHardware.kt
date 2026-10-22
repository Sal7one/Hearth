package com.sal7one.hearth.desktop.ui.screens.models

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelCard

/** Implemented desktop adapters, separate from the current host's usable devices. */
internal fun desktopModelGpuPaths(card: DesktopModelCard): Set<String> = when {
    card.engineId == "whisper-local" && card.stage == PipelineStage.STT -> setOf("metal", "cuda")
    card.engineId == "llama-translation-local" && card.stage == PipelineStage.TRANSLATION -> setOf("metal", "cuda")
    else -> emptySet()
}

internal fun availableModelGpuPaths(card: DesktopModelCard, engines: List<EngineDescriptor>): Set<String> {
    val engine = engines.firstOrNull { it.id == card.engineId && it.stage == card.stage && it.kind == EngineKind.LOCAL && it.available }
    return desktopModelGpuPaths(card).intersect(engine?.acceleration.orEmpty())
}

internal enum class ModelHardwareFilter(val key: String, val label: String, val detail: String) {
    ALL("all", "All hardware", "GPU labels describe Hearth's current adapters. Filtering does not change compute settings."),
    AVAILABLE("available-gpu", "GPU available here", "Only models whose engine reports a usable GPU on this computer. Inference still verifies model placement."),
    GPU("gpu", "All GPU paths", "Whisper and GGUF translation have Metal and Windows CUDA paths. NVIDIA hardware validation is pending."),
    METAL("metal", "Apple Metal", "Models with a macOS Metal path. Availability on this computer is shown on each card."),
    CUDA("cuda", "NVIDIA CUDA", "Models with a Windows CUDA build path. Actual NVIDIA hardware validation is pending."),
    CPU_ONLY("cpu-only", "CPU only", "Models whose current desktop adapter has no GPU path. GPU-capable models can also run on CPU.");

    fun matches(paths: Set<String>, available: Set<String>): Boolean = when (this) {
        ALL -> true
        AVAILABLE -> available.isNotEmpty()
        GPU -> paths.isNotEmpty()
        METAL -> "metal" in paths
        CUDA -> "cuda" in paths
        CPU_ONLY -> paths.isEmpty()
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ALL
    }
}

internal fun modelGpuLabel(paths: Set<String>, available: Set<String>): String? {
    if (paths.isEmpty()) return null
    fun label(key: String) = if (key == "metal") "Metal" else "NVIDIA CUDA"
    val supported = paths.joinToString(" · ", transform = ::label)
    return if (available.isEmpty()) "$supported paths · GPU unavailable here"
        else "$supported paths · ${available.joinToString(" · ", transform = ::label)} available here"
}
