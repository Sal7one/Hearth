package com.sal7one.hearth.desktop.ui.screens.presets

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.models.ModelPerformanceCatalog
import com.sal7one.hearth.core.models.PerformancePlatform
import com.sal7one.hearth.desktop.ModelTransfer
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.language.languageLabel
import com.sal7one.hearth.desktop.ui.language.pipelineLanguages

internal fun presetsState(
    definitions: List<PresetDefinition>,
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    config: PipelineConfig,
    engines: List<EngineDescriptor>? = null,
    transfers: Map<String, ModelTransfer> = emptyMap(),
    verifying: Set<String> = emptySet(),
    verifiedPath: (String) -> String? = { id -> installations[id]?.takeIf { it.ready }?.path?.toString() },
): PresetsState {
    val cards = catalog.associateBy { it.id }
    val supported = definitions.filter { preset ->
        preset.stages.all { (stage, id) -> cards[id]?.stage == stage }
    }
    fun unavailable(card: DesktopModelCard): String? {
        val engine = engines?.firstOrNull { it.id == card.engineId && it.stage == card.stage }
        if (engines == null || engine?.available == true) return null
        return engine?.unavailableReason ?: "${card.name} cannot run on this desktop build."
    }
    val hasCompactStarter = supported.any { preset ->
        preset.featured && !preset.fallback && preset.stages.values.none { unavailable(cards.getValue(it)) != null }
    }
    return PresetsState(supported.filter { !it.fallback || !hasCompactStarter }.map { preset ->
        val presetCards = preset.stages.values.map(cards::getValue)
        val verifiedPaths = presetCards.associate { it.id to verifiedPath(it.id) }
        val stages = presetCards.map { card ->
            val ready = verifiedPaths[card.id] != null
            val transfer = transfers[card.id]
            val activity = when {
                ready -> null
                transfer != null && (!transfer.paused || transfer.cancelling) -> transfer.label
                installations[card.id]?.checking == true || card.id in verifying -> "Checking files"
                else -> null
            }
            val stage = requireNotNull(card.stage)
            PresetStage(stageLabel(stage), card.name, ready, activity)
        }
        val downloads = presetCards.filterIndexed { index, _ -> !stages[index].installed && stages[index].activity == null }
        val source = preset.sourceLanguage
        val target = preset.targetLanguage
        val active = PipelineStage.entries.all { stage ->
            val route = config.selection(stage)
            val id = preset.stages[stage]
            if (id == null) route == null
            else route?.modelId == id && route.engineId == cards.getValue(id).engineId && route.modelPath == verifiedPaths[id]
        } &&
            config.sourceLanguage == source && (target == null || target == config.targetLanguage) &&
            (preset.voiceId == null || config.voiceId == preset.voiceStyle && config.speakTranslatedText)

        PresetView(
            id = preset.id,
            title = preset.title,
            description = preset.description,
            languages = if (target == null) "${languageLabel(source)} · original captions"
                else "${languageLabel(source)} → ${languageLabel(target)}",
            stages = stages,
            active = active,
            downloadIds = downloads.map { it.id },
            downloadBytes = downloads.sumOf { it.bytes },
            totalBytes = presetCards.sumOf { it.bytes },
            featured = preset.featured,
            unavailableReason = presetCards.firstNotNullOfOrNull(::unavailable),
            measurements = presetCards.flatMap { card ->
                ModelPerformanceCatalog.forModel(card.id, PerformancePlatform.MACOS).filter { reading ->
                    when (card.stage) {
                        PipelineStage.STT -> source == "auto" || reading.language == source
                        PipelineStage.TRANSLATION -> reading.language == "$source→$target"
                        PipelineStage.TTS -> reading.language == target && reading.title.endsWith(" · ${preset.voiceStyle}")
                        null -> false
                    }
                }
            },
        )
    })
}

internal fun applyDesktopPreset(
    current: PipelineConfig,
    definition: PresetDefinition,
    engines: List<EngineDescriptor>,
    catalog: List<DesktopModelCard>,
    verified: (String) -> EngineSelection?,
): PipelineConfig {
    val routes = definition.stages.mapValues { (stage, id) ->
        val card = requireNotNull(catalog.firstOrNull { it.id == id && it.stage == stage }) {
            "The preset is unavailable on this build."
        }
        val engine = engines.firstOrNull { it.id == card.engineId && it.stage == stage }
        require(engine?.available == true) { engine?.unavailableReason ?: "${card.name} is unavailable on this computer." }
        val selection = requireNotNull(verified(id)?.takeIf {
            it.engineId == card.engineId && it.modelId == id && it.modelPath.isNotBlank()
        }) {
            "Finish preparing ${card.name} before using this preset."
        }
        val previous = current.selection(stage)?.takeIf { it.engineId == card.engineId && it.modelId == id }
        selection.copy(options = previous?.options.orEmpty() + selection.options)
    }
    val result = current.copy(
        speech = routes[PipelineStage.STT],
        translation = routes[PipelineStage.TRANSLATION],
        voice = routes[PipelineStage.TTS],
        sourceLanguage = definition.sourceLanguage,
        targetLanguage = definition.targetLanguage ?: current.targetLanguage.takeUnless { it == "auto" } ?: "en",
        voiceId = definition.voiceStyle,
        speakTranslatedText = if (definition.voiceId != null) true else current.speakTranslatedText,
    )
    val issue = pipelineLanguages(result, engines, catalog).issue
    require(issue == null) { requireNotNull(issue) }
    return result
}

private fun stageLabel(stage: PipelineStage): String = when (stage) {
    PipelineStage.STT -> PresetsCopy.RECOGNIZE
    PipelineStage.TRANSLATION -> PresetsCopy.TRANSLATE
    PipelineStage.TTS -> PresetsCopy.SPEAK
}
