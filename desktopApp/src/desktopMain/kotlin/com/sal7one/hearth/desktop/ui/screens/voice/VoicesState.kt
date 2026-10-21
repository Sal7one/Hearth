package com.sal7one.hearth.desktop.ui.screens.voice

import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption

/** The ten Supertonic styles. Ids only — Hearth has no authored voice names. */
internal val VOICE_STYLES: List<String> = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

internal data class VoicesState(
    val engines: List<ChoiceOption> = emptyList(),
    val selectedEngineId: String? = null,
    val styles: List<String> = VOICE_STYLES,
    val selectedStyle: String = "F1",
    val speed: Float = 1f,
    val steps: Int = 5,
    val readAloud: Boolean = false,
    val speakTranslation: Boolean = true,
    val ready: Boolean = false,
    val engineDetail: String = "Not configured",
    val placementLabel: String = "CPU",
) {
    val voiceOff: Boolean get() = selectedEngineId == null
    val supertonicSelected: Boolean get() = selectedEngineId == "supertonic-local"
}
