package com.sal7one.hearth.desktop.ui.screens.speech

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.runtime.AudioCaptureDevice
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption
import com.sal7one.hearth.desktop.ui.screens.settings.connectionConfigured
import com.sal7one.hearth.desktop.ui.screens.settings.engineRuntimeLabel
import com.sal7one.hearth.desktop.ui.screens.settings.verifiedSelectionMatches
import com.sal7one.hearth.desktop.ui.screens.settings.computeSettings
import com.sal7one.hearth.desktop.ui.language.pipelineLanguages

/**
 * Resolves the speech page from the real descriptors, catalog, saved configuration
 * and enumerated capture devices. Options exposed here are the keys the runtime
 * actually reads (`windowMs`, `acceleration`, `threads`) — nothing invented.
 */
internal fun speechState(
    capabilities: List<EngineDescriptor>,
    config: PipelineConfig,
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    devices: List<AudioCaptureDevice>,
    deviceId: String?,
): SpeechState {
    val speechEngines = capabilities.filter { it.stage == PipelineStage.STT }
    val chosen = config.selection(PipelineStage.STT)
    val descriptor = speechEngines.firstOrNull { it.id == chosen?.engineId }
    val languages = pipelineLanguages(config, capabilities, catalog)

    fun engineOption(engine: EngineDescriptor): ChoiceOption = ChoiceOption(
        id = engine.id,
        badge = if (engine.kind == EngineKind.LOCAL) "L" else "C",
        name = engine.name,
        detail = engineRuntimeLabel(engine),
        note = if (engine.available) {
            if (engine.kind == EngineKind.LOCAL) "Audio never leaves the machine." else "Sends audio to an endpoint you control."
        } else {
            ""
        },
        available = engine.available,
        unavailableReason = engine.unavailableReason,
    )

    val compatibleModels = catalog.filter { it.stage == PipelineStage.STT && it.engineId == descriptor?.id }
    val models = compatibleModels.map { card ->
        SpeechModelOption(
            id = card.id,
            label = card.name,
            installed = installations[card.id]?.ready == true,
        )
    }

    val modelCard = compatibleModels.firstOrNull { it.id == chosen?.modelId }
    val selectedModelId = modelCard?.id
    val verified = verifiedSelectionMatches(chosen, modelCard, modelCard?.let { installations[it.id] })

    val compute = computeSettings(descriptor, chosen)

    val windowMs = chosen?.options?.get("windowMs")?.toIntOrNull()
    val responsiveness = Responsiveness.fromWindow(windowMs)
    val threads = chosen?.options?.get("threads")?.toIntOrNull() ?: 4

    val deviceOptions = buildList {
        add(SelectOption("", "System default"))
        devices.forEach { device -> add(SelectOption(device.id, device.name)) }
    }

    return SpeechState(
        engines = speechEngines.map(::engineOption),
        selectedEngineId = descriptor?.id,
        selectedKind = descriptor?.kind,
        models = models,
        selectedModelId = selectedModelId,
        modelVerified = verified,
        language = config.sourceLanguage,
        languageOptions = languages.sources.map { SelectOption(it.code, it.label) },
        languageIssue = languages.issue,
        devices = deviceOptions,
        selectedDevice = deviceId ?: "",
        responsiveness = responsiveness,
        compute = compute.selected,
        computeOptions = compute.options,
        computeIssue = compute.issue,
        threads = threads,
        ready = descriptor?.available == true && languages.issue == null && compute.issue == null &&
            if (descriptor.kind == EngineKind.CLOUD) connectionConfigured(descriptor, chosen) else verified,
        placementLabel = if (descriptor?.kind == EngineKind.CLOUD) "Provider" else compute.selected.label + if (compute.issue != null) " unavailable" else "",
        windowLabel = "${responsiveness.windowMs / 1000} s windows",
        modelDetail = modelCard?.name ?: if (descriptor?.kind == EngineKind.CLOUD) chosen?.modelId?.ifBlank { descriptor.defaultModel }.orEmpty() else SpeechCopy.NOT_CONFIGURED,
        unavailableReason = descriptor?.takeIf { !it.available }?.unavailableReason,
    )
}

/** The languages a caption session realistically starts from; the picker is not a claim of quality. */
internal fun languageOptions(): List<SelectOption> = listOf(
    SelectOption("auto", SpeechCopy.AUTO_LABEL),
    SelectOption("en", "English"),
    SelectOption("ar", "العربية"),
    SelectOption("de", "Deutsch"),
    SelectOption("fr", "Français"),
    SelectOption("es", "Español"),
    SelectOption("ja", "日本語"),
    SelectOption("zh", "中文"),
)

internal fun languageNameFor(code: String): String =
    languageOptions().firstOrNull { it.id == code }?.label ?: code.uppercase()
