package com.sal7one.hearth.desktop.ui.screens.appearance

import androidx.compose.ui.graphics.Color
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.hearthColors

/** One palette as three swatch bars: accent, raised surface, ground. */
internal data class PaletteOption(
    val preset: AccentPreset,
    val accent: Color,
    val raised: Color,
    val ground: Color,
)

internal data class AppearanceState(
    val palettes: List<PaletteOption> = emptyList(),
    val selected: AccentPreset = AccentPreset.INK,
    val dark: Boolean = true,
    val reduceMotion: Boolean = false,
) {
    val brightnessLabel: String get() = if (dark) "Dark" else "Light"
}

/** Every palette in the current brightness, resolved through the same token builder the app uses. */
internal fun appearanceState(selected: AccentPreset, dark: Boolean, reduceMotion: Boolean): AppearanceState =
    AppearanceState(
        palettes = AccentPreset.entries.map { preset ->
            val colors = hearthColors(preset, dark)
            PaletteOption(preset, colors.accent, colors.raised, colors.bg)
        },
        selected = selected,
        dark = dark,
        reduceMotion = reduceMotion,
    )
