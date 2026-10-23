package com.sal7one.hearth.desktop.ui.screens.presets

import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Preset callbacks. Applying a preset either changes all three stages or sends the
 * operator to the models it still needs — it never half-applies.
 */
internal class PresetsActions(
    val onApply: (String) -> Unit = {},
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
)
