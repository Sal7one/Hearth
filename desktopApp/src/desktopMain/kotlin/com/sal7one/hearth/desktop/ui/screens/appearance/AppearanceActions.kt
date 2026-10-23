package com.sal7one.hearth.desktop.ui.screens.appearance

import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.theme.AccentPreset

/**
 * Appearance callbacks. Selecting a palette applies immediately; there is no Apply
 * button because the whole window is the preview.
 */
internal class AppearanceActions(
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onPalette: (AccentPreset) -> Unit = {},
    val onDark: (Boolean) -> Unit = {},
    val onReduceMotion: (Boolean) -> Unit = {},
)
