package com.sal7one.hearth.desktop.ui.screens.voice

import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Voice callbacks. Turning the stage off clears `config.voice` entirely, which is
 * the only honest way to stop speech: it removes the route rather than muting it.
 */
internal class VoicesActions(
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onSelectEngine: (String?) -> Unit = {},
    val onSelectStyle: (String) -> Unit = {},
    val onSpeakTranslation: (Boolean) -> Unit = {},
    val onToggleReadAloud: (Boolean) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onSteps: (Int) -> Unit = {},
    val onStopAudio: () -> Unit = {},
)
