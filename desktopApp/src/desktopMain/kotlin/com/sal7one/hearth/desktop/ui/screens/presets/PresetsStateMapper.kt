package com.sal7one.hearth.desktop.ui.screens.presets

import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes

/**
 * Resolves each preset against the catalog, the installation map and the saved
 * configuration. "Active" means all three stages already point at this preset's
 * models — including the stages the preset leaves off.
 */
internal fun presetsState(
    definitions: List<PresetDefinition>,
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    config: PipelineConfig,
): PresetsState {
    fun card(id: String?): DesktopModelCard? = id?.let { wanted -> catalog.firstOrNull { it.id == wanted } }
    fun installed(id: String?): Boolean = id != null && installations[id]?.ready == true

    val views = definitions.map { definition ->
        val speech = card(definition.speechId)
        val translation = card(definition.translationId)
        val voice = card(definition.voiceId)

        val stages = listOf(
            PresetStage(1, PipelineStage.STT, PresetsCopy.RECOGNIZE, speech?.name, installed(definition.speechId), definition.speechId != null),
            PresetStage(2, PipelineStage.TRANSLATION, PresetsCopy.TRANSLATE, translation?.name, installed(definition.translationId), definition.translationId != null),
            PresetStage(3, PipelineStage.TTS, PresetsCopy.SPEAK, voice?.name, installed(definition.voiceId), definition.voiceId != null),
        )

        val missing = listOfNotNull(
            definition.speechId?.takeIf { speech == null }?.let { "Speech model is no longer in the catalog" },
            definition.translationId?.takeIf { translation == null }?.let { "Translation model is no longer in the catalog" },
            definition.voiceId?.takeIf { voice == null }?.let { "Voice model is no longer in the catalog" },
            speech?.takeIf { !installed(definition.speechId) }?.name,
            translation?.takeIf { !installed(definition.translationId) }?.name,
            voice?.takeIf { !installed(definition.voiceId) }?.name,
        )

        val active = config.selection(PipelineStage.STT)?.modelId.orEmpty() == definition.speechId.orEmpty() &&
            config.selection(PipelineStage.TRANSLATION)?.modelId.orEmpty() == definition.translationId.orEmpty() &&
            config.selection(PipelineStage.TTS)?.modelId.orEmpty() == definition.voiceId.orEmpty() &&
            (definition.speechId != null || config.speech == null)

        val bytes = listOfNotNull(speech, translation, voice).sumOf { it.bytes }

        PresetView(
            id = definition.id,
            title = definition.title,
            description = definition.description,
            stages = stages,
            active = active,
            missing = missing,
            sizeLabel = if (bytes > 0) formatBytes(bytes) else "No downloads",
            featured = definition.featured,
        )
    }

    return PresetsState(
        presets = views,
        installedCount = catalog.count { installations[it.id]?.ready == true },
    )
}
