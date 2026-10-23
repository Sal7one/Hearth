package com.sal7one.hearth.desktop.ui.screens.downloads

import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.data.DesktopDownloadOptions

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
    val onPauseAll: () -> Unit = {},
    val onResumeAll: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onRestart: (String) -> Unit = {},
    val onDownloadAll: (List<String>) -> Unit = {},
    val onConfigure: (DesktopDownloadOptions) -> Unit = {},
    val onChooseAria2: () -> Unit = {},
    val onExportMeasurements: () -> Unit = {},
)
