package com.sal7one.hearth.desktop.ui.screens.speech

import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption

/** How long the recogniser waits before committing a line. Maps to `windowMs`. */
internal enum class Responsiveness(val label: String, val windowMs: Int, val detail: String) {
    RESPONSIVE("Responsive", 2_000, "Commits sooner; may split a sentence."),
    STANDARD("Standard", 4_000, "Four-second windows. The default."),
    PATIENT("Patient", 12_000, "Waits longer; better for slow, formal speech.");

    companion object {
        fun fromWindow(windowMs: Int?): Responsiveness = when {
            windowMs == null -> STANDARD
            windowMs <= 3_000 -> RESPONSIVE
            windowMs >= 9_000 -> PATIENT
            else -> STANDARD
        }
    }
}

/** Mirrors the runtime's `acceleration` option. */
internal enum class Compute(val label: String, val key: String) {
    CUDA("NVIDIA CUDA", "cuda"),
    METAL("Metal", "metal"),
    CPU("CPU", "cpu");

    companion object {
        fun fromKey(key: String?): Compute? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

internal data class SpeechModelOption(val id: String, val label: String, val installed: Boolean)

internal data class SpeechState(
    val engines: List<ChoiceOption> = emptyList(),
    val selectedEngineId: String? = null,
    val selectedKind: EngineKind? = null,
    val models: List<SpeechModelOption> = emptyList(),
    val selectedModelId: String? = null,
    val modelVerified: Boolean = false,
    val language: String = "auto",
    val languageOptions: List<SelectOption> = emptyList(),
    val languageIssue: String? = null,
    val devices: List<SelectOption> = emptyList(),
    val selectedDevice: String? = null,
    val responsiveness: Responsiveness = Responsiveness.STANDARD,
    val compute: Compute = Compute.CPU,
    val computeOptions: List<Compute> = listOf(Compute.CPU),
    val threads: Int = 4,
    val ready: Boolean = false,
    val placementLabel: String = "CPU",
    val windowLabel: String = "4 s windows",
    val modelDetail: String = "No model selected",
    val unavailableReason: String? = null,
    val computeIssue: String? = null,
) {
    val localSelected: Boolean get() = selectedKind == EngineKind.LOCAL
    val cloudSelected: Boolean get() = selectedKind == EngineKind.CLOUD
    val selectedModel: SpeechModelOption? get() = models.firstOrNull { it.id == selectedModelId }
}
