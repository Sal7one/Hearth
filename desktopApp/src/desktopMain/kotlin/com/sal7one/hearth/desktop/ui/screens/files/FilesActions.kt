package com.sal7one.hearth.desktop.ui.screens.files

import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Storage callbacks. Export and import take a path the operator chose; nothing is
 * written to a hidden location on their behalf.
 */
internal class FilesActions(
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onExportSetup: () -> Unit = {},
    val onImportSetup: () -> Unit = {},
)
