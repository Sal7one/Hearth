package com.sal7one.hearth.desktop.ui.screens.models

import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Library callbacks. Acting on a model is deliberately split: [onAction] installs or
 * activates, and never does both, because "downloaded" is not "selected".
 */
internal class ModelsActions(
    val onSearch: (String) -> Unit = {},
    val onFilterStage: (PipelineStage?) -> Unit = {},
    val onToggleInstalledOnly: () -> Unit = {},
    val onAction: (String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenFolder: () -> Unit = {},
    val onNavigate: (DesktopScreen) -> Unit = {},
)
