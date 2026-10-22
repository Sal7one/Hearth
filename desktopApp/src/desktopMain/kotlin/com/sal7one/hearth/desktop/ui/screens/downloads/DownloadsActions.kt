package com.sal7one.hearth.desktop.ui.screens.downloads

import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Transfer callbacks. Downloading never implies activating: that stays on the
 * library page where the operator chooses a model for a stage.
 */
internal class DownloadsActions(
    val onDownload: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenFolder: () -> Unit = {},
    val onNavigate: (DesktopScreen) -> Unit = {},
)
