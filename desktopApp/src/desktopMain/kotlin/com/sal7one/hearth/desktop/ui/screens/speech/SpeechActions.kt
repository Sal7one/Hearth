package com.sal7one.hearth.desktop.ui.screens.speech

import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Speech callbacks. Each option write goes through the controller, so the saved
 * configuration stays the single source of truth for what a session will run.
 */
internal class SpeechActions(
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onSelectEngine: (String) -> Unit = {},
    val onSelectModel: (String) -> Unit = {},
    val onLanguage: (String) -> Unit = {},
    val onDevice: (String?) -> Unit = {},
    val onRefreshDevices: () -> Unit = {},
    val onResponsiveness: (Responsiveness) -> Unit = {},
    val onCompute: (Compute) -> Unit = {},
    val onThreads: (Int) -> Unit = {},
    val onTestMicrophone: () -> Unit = {},
)
