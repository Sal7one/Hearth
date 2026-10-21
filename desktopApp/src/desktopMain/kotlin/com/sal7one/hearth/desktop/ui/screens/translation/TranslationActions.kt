package com.sal7one.hearth.desktop.ui.screens.translation

import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.screens.speech.Compute

/**
 * Translation callbacks. Selecting an engine replaces the whole route, so a stale
 * prompt mode from the previous model cannot leak into the new one.
 */
internal class TranslationActions(
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onSelectEngine: (String?) -> Unit = {},
    val onSelectModel: (String) -> Unit = {},
    val onSourceLanguage: (String) -> Unit = {},
    val onTargetLanguage: (String) -> Unit = {},
    val onPromptMode: (PromptMode) -> Unit = {},
    val onCompute: (Compute) -> Unit = {},
    val onThreads: (Int) -> Unit = {},
)
