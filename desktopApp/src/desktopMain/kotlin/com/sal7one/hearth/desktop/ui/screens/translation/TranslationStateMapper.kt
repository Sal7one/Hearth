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
import com.sal7one.hearth.desktop.ui.screens.speech.languageOptions

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

    val models = catalog.filter { it.stage == PipelineStage.TRANSLATION }.map { card ->
        SelectOption(
            id = card.id,
            label = card.name,
            detail = if (installations[card.id]?.ready == true) "Installed" else "Not installed",
        )
    }

    val selectedModelId = chosen?.modelId?.takeIf { it.isNotBlank() }
    val verified = selectedModelId != null && installations[selectedModelId]?.ready == true
    val modelCard = catalog.firstOrNull { it.id == selectedModelId }

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
                name = if (engine.kind == EngineKind.LOCAL) "On this Mac" else engine.name,
                detail = if (engine.kind == EngineKind.LOCAL) "llama.cpp · ${engine.acceleration.joinToString("/")}" else "OpenAI-compatible chat",
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
        models = models,
        selectedModelId = selectedModelId,
        verified = verified,
        sourceLanguage = config.sourceLanguage,
        targetLanguage = config.targetLanguage,
        languageOptions = languageOptions(),
        promptMode = PromptMode.fromKey(chosen?.options?.get("promptMode")),
        compute = compute,
        computeOptions = computeOptions,
        threads = chosen?.options?.get("threads")?.toIntOrNull() ?: 4,
        ready = descriptor?.available == true && verified,
        modelDetail = modelCard?.name ?: TranslationCopy.NOT_CONFIGURED,
        placementLabel = compute.label,
    )
}
