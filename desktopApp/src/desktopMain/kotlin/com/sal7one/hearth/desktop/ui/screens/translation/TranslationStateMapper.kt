package com.sal7one.hearth.desktop.ui.screens.translation

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption
import com.sal7one.hearth.desktop.ui.screens.speech.Compute
import com.sal7one.hearth.desktop.ui.screens.settings.connectionConfigured
import com.sal7one.hearth.desktop.ui.screens.settings.engineRuntimeLabel
import com.sal7one.hearth.desktop.ui.screens.settings.verifiedSelectionMatches
import com.sal7one.hearth.desktop.ui.language.pipelineLanguages

/**
 * Resolves translation settings from the real descriptors and configuration.
 * `promptMode`, `threads` and `acceleration` are the keys the llama.cpp owner reads.
 */
internal fun translationState(
    capabilities: List<EngineDescriptor>,
    config: PipelineConfig,
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
): TranslationState {
    val engines = capabilities.filter { it.stage == PipelineStage.TRANSLATION }
    val chosen = config.selection(PipelineStage.TRANSLATION)
    val descriptor = engines.firstOrNull { it.id == chosen?.engineId }
    val languages = pipelineLanguages(config, capabilities, catalog, typedText = true)

    val compatibleModels = catalog.filter { it.stage == PipelineStage.TRANSLATION && it.engineId == descriptor?.id }
    val models = compatibleModels.map { card ->
        SelectOption(
            id = card.id,
            label = card.name,
            detail = if (installations[card.id]?.ready == true) "Installed" else "Not installed",
        )
    }

    val modelCard = compatibleModels.firstOrNull { it.id == chosen?.modelId }
    val selectedModelId = modelCard?.id
    val verified = verifiedSelectionMatches(chosen, modelCard, modelCard?.let { installations[it.id] })

    val accelerationKeys = descriptor?.acceleration.orEmpty()
    val computeOptions = buildList {
        if (accelerationKeys.any { it.equals("metal", ignoreCase = true) }) add(Compute.METAL)
        add(Compute.CPU)
    }
    val compute = if (chosen?.options?.get("acceleration")?.equals("metal", ignoreCase = true) == true) {
        Compute.METAL
    } else {
        Compute.CPU
    }

    return TranslationState(
        engines = engines.map { engine ->
            ChoiceOption(
                id = engine.id,
                badge = if (engine.kind == EngineKind.LOCAL) "L" else "C",
                name = engine.name,
                detail = engineRuntimeLabel(engine),
                note = if (engine.available) {
                    if (engine.kind == EngineKind.LOCAL) "Text stays local." else "Any endpoint that speaks chat completions."
                } else {
                    ""
                },
                available = engine.available,
                unavailableReason = engine.unavailableReason,
            )
        },
        selectedEngineId = descriptor?.id,
        selectedKind = descriptor?.kind,
        models = models,
        selectedModelId = selectedModelId,
        verified = verified,
        sourceLanguage = config.sourceLanguage,
        targetLanguage = config.targetLanguage,
        languageOptions = languages.sources.map { SelectOption(it.code, it.label) },
        sourceOptions = languages.sources.map { SelectOption(it.code, it.label) },
        targetOptions = languages.targets.map { SelectOption(it.code, it.label) },
        languageIssue = languages.issue,
        promptMode = PromptMode.fromKey(chosen?.options?.get("promptMode")),
        compute = compute,
        computeOptions = computeOptions,
        threads = chosen?.options?.get("threads")?.toIntOrNull() ?: 4,
        ready = descriptor?.available == true && languages.issue == null &&
            if (descriptor.kind == EngineKind.CLOUD) connectionConfigured(descriptor, chosen) else verified,
        modelDetail = modelCard?.name ?: if (descriptor?.kind == EngineKind.CLOUD) chosen?.modelId?.ifBlank { descriptor.defaultModel }.orEmpty() else TranslationCopy.NOT_CONFIGURED,
        placementLabel = if (descriptor?.kind == EngineKind.CLOUD) "Provider" else compute.label,
    )
}
