package com.sal7one.hearth.desktop.ui.screens.home

import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.art.HearthArt

/** Whether a capability exists on this build today. Drives the tile footnote. */
internal enum class Availability(val label: String) { DESKTOP("Desktop"), MOBILE("Mobile") }

/** One entry in the Home map. The art is optional so a system tile can exist. */
internal data class HomeFeature(
    val id: String,
    val title: String,
    val description: String,
    val available: Availability,
    val art: HearthArt? = null,
    val destination: DesktopScreen? = null,
)

/**
 * Everything Home renders, resolved before composition so the screen itself stays
 * a pure function of state. Counts here are real: they come from the pipeline's
 * engine descriptors and the model library, never from a literal on screen.
 */
internal data class HomeState(
    val sourceLanguage: String = "en",
    val targetLanguage: String = "ar",
    val sourceLabel: String = "English",
    val targetLabel: String = "العربية",
    val speechEngine: String? = null,
    val translationEngine: String? = null,
    val voiceEngine: String? = null,
    val installedModels: Int = 0,
    val verifiedModels: Int = 0,
    val recognitionReady: Boolean = false,
    val statusText: String = "Not configured",
    val statusLive: Boolean = false,
) {
    val subtitleChips: List<String>
        get() = buildList {
            speechEngine?.let(::add)
            translationEngine?.let(::add)
            add(voiceEngine ?: "Voice off")
        }
}

/** Dark-surface scrim strength shared by every artwork tile. */
internal object TileScrim {
    const val IMAGE_ALPHA = 0.92f
    const val TOP_FADE = 0.42f
    const val BOTTOM_FADE = 0.88f
}
