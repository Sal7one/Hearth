package com.sal7one.hearth.desktop.ui.screens.presets

import com.sal7one.hearth.core.PipelineStage

/**
 * A preset names catalog models, not paths. `null` means "this preset leaves that
 * stage off" — which is different from "the model is missing".
 */
internal data class PresetDefinition(
    val id: String,
    val title: String,
    val description: String,
    val speechId: String? = null,
    val translationId: String? = null,
    val voiceId: String? = null,
    val voiceStyle: String = "",
    val featured: Boolean = false,
)

internal data class PresetStage(
    val index: Int,
    val stage: PipelineStage,
    val label: String,
    val modelName: String?,
    val installed: Boolean,
    val referenced: Boolean,
) {
    /** The preset does not use this stage at all. */
    val off: Boolean get() = !referenced

    /** The preset names a model that is not in this build's catalog. */
    val unavailable: Boolean get() = referenced && modelName == null
}

internal data class PresetView(
    val id: String,
    val title: String,
    val description: String,
    val stages: List<PresetStage>,
    val active: Boolean,
    val missing: List<String>,
    val sizeLabel: String,
    val featured: Boolean,
) {
    val ready: Boolean get() = missing.isEmpty()
    val statusLabel: String
        get() = when {
            active -> "Active"
            missing.isEmpty() -> "Ready"
            missing.size == 1 -> "1 download"
            else -> "${missing.size} missing"
        }
}

internal data class PresetsState(
    val presets: List<PresetView> = emptyList(),
    val installedCount: Int = 0,
) {
    val activePreset: PresetView? get() = presets.firstOrNull { it.active }
}
