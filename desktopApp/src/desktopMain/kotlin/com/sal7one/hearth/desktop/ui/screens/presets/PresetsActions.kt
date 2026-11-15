package com.sal7one.hearth.desktop.ui.screens.presets

import com.sal7one.hearth.desktop.ui.DesktopScreen

internal class PresetsActions(
    val onApply: (String) -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
)
