package com.sal7one.hearth.desktop.ui.screens.translate

/** Typed-translation callbacks. Starting a session goes through the controller. */
internal class TranslateActions(
    val onTranslate: (String) -> Unit = {},
    val onSwapLanguages: () -> Unit = {},
    val onCopyResult: () -> Unit = {},
    val onOpenVoice: () -> Unit = {},
    val onSourceLanguage: (String) -> Unit = {},
    val onTargetLanguage: (String) -> Unit = {},
    val onOpenSetup: () -> Unit = {},
    val onOpenCaptions: () -> Unit = {},
)
