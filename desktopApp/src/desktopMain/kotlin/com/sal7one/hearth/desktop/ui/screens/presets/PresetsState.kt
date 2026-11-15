package com.sal7one.hearth.desktop.ui.screens.presets

import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.models.ModelMeasurement

internal data class PresetDefinition(
    val id: String,
    val title: String,
    val description: String,
    val speechId: String,
    val translationId: String? = null,
    val voiceId: String? = null,
    val voiceStyle: String = "",
    val sourceLanguage: String,
    val targetLanguage: String? = null,
    val featured: Boolean = false,
    val fallback: Boolean = false,
) {
    val stages: Map<PipelineStage, String> get() = buildMap {
        put(PipelineStage.STT, speechId)
        translationId?.let { put(PipelineStage.TRANSLATION, it) }
        voiceId?.let { put(PipelineStage.TTS, it) }
    }
}

internal data class PresetStage(
    val label: String,
    val modelName: String,
    val installed: Boolean,
    val activity: String? = null,
)

internal data class PresetView(
    val id: String,
    val title: String,
    val description: String,
    val languages: String,
    val stages: List<PresetStage>,
    val active: Boolean,
    val downloadIds: List<String>,
    val downloadBytes: Long,
    val totalBytes: Long,
    val featured: Boolean,
    val unavailableReason: String? = null,
    val measurements: List<ModelMeasurement> = emptyList(),
) {
    val ready: Boolean get() = unavailableReason == null && stages.all { it.installed }
    val statusLabel: String
        get() = when {
            unavailableReason != null -> "Unavailable"
            !ready && stages.any { it.activity != null } -> "Preparing"
            !ready -> "Needs download"
            active -> "Active"
            else -> "Ready"
        }
}

internal data class PresetsState(
    val presets: List<PresetView> = emptyList(),
) {
    val starter: PresetView? get() = presets.firstOrNull { it.featured && it.ready }
        ?: presets.firstOrNull { it.featured && it.unavailableReason == null }
}
