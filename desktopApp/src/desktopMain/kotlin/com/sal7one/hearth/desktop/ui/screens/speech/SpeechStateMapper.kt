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

    fun engineOption(engine: EngineDescriptor): ChoiceOption = ChoiceOption(
        id = engine.id,
        badge = if (engine.kind == EngineKind.LOCAL) "L" else "C",
        name = if (engine.kind == EngineKind.LOCAL) "On this Mac" else engine.name,
        detail = if (engine.kind == EngineKind.LOCAL) "whisper.cpp · ${engine.acceleration.joinToString("/")}" else "OpenAI-compatible",
        note = if (engine.available) {
            if (engine.kind == EngineKind.LOCAL) "Audio never leaves the machine." else "Sends audio to an endpoint you control."
        } else {
            ""
        },
        available = engine.available,
        unavailableReason = engine.unavailableReason,
    )

    val models = catalog.filter { it.stage == PipelineStage.STT }.map { card ->
        SpeechModelOption(
            id = card.id,
            label = card.name,
            installed = installations[card.id]?.ready == true,
        )
    }

    val selectedModelId = chosen?.modelId?.takeIf { it.isNotBlank() }
    val modelCard = catalog.firstOrNull { it.id == selectedModelId }
    val verified = selectedModelId != null && installations[selectedModelId]?.ready == true

    val accelerationKeys = descriptor?.acceleration.orEmpty()
    val computeOptions = buildList {
        if (accelerationKeys.any { it.equals("metal", ignoreCase = true) }) add(Compute.METAL)
        add(Compute.CPU)
    }
    val compute = when {
        chosen?.options?.get("acceleration")?.equals("metal", ignoreCase = true) == true -> Compute.METAL
        else -> Compute.CPU
    }

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
        models = models,
        selectedModelId = selectedModelId,
        modelVerified = verified,
        language = config.sourceLanguage,
        languageOptions = languageOptions(),
        devices = deviceOptions,
        selectedDevice = deviceId ?: "",
        responsiveness = responsiveness,
        compute = compute,
        computeOptions = computeOptions,
        threads = threads,
        ready = descriptor?.available == true && verified,
        placementLabel = compute.label,
        windowLabel = "${responsiveness.windowMs / 1000} s windows",
        modelDetail = modelCard?.name ?: SpeechCopy.NOT_CONFIGURED,
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
