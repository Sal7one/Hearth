package com.sal7one.hearth.desktop.ui.screens.translation

import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption
import com.sal7one.hearth.desktop.ui.screens.speech.Compute

/** The prompt protocol the runtime selects with `promptMode`. */
internal enum class PromptMode(val key: String, val label: String, val detail: String) {
    DEFAULT("default", "Hearth default", "The generic translation prompt."),
    RAW("raw", "Raw text", "Sends the line with no instruction wrapper."),
    EURO("euro", "EuroLLM", "The EuroLLM template."),
    RIVA("riva", "Riva-Translate", "The Riva template.");

    companion object {
        fun fromKey(key: String?): PromptMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

internal data class TranslationState(
    val engines: List<ChoiceOption> = emptyList(),
    val selectedEngineId: String? = null,
    val selectedKind: EngineKind? = null,
    val models: List<SelectOption> = emptyList(),
    val selectedModelId: String? = null,
    val verified: Boolean = false,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "ar",
    val languageOptions: List<SelectOption> = emptyList(),
    val sourceOptions: List<SelectOption> = emptyList(),
    val targetOptions: List<SelectOption> = emptyList(),
    val languageIssue: String? = null,
    val promptMode: PromptMode = PromptMode.DEFAULT,
    val compute: Compute = Compute.CPU,
    val computeOptions: List<Compute> = listOf(Compute.CPU),
    val threads: Int = 4,
    val ready: Boolean = false,
    val modelDetail: String = "No model selected",
    val placementLabel: String = "CPU",
) {
    val translationOff: Boolean get() = selectedEngineId == null
    val localSelected: Boolean get() = selectedKind == EngineKind.LOCAL
    val cloudSelected: Boolean get() = selectedKind == EngineKind.CLOUD
    val supportsPromptMode: Boolean get() = selectedEngineId == "llama-translation-local"
}
