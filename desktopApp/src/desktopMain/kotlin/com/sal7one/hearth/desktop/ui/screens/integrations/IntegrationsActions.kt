package com.sal7one.hearth.desktop.ui.screens.integrations

/** Integration callbacks. Copying a token is always an explicit operator action. */
internal class IntegrationsActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onCopyOverlayUrl: () -> Unit = {},
    val onCopyApiToken: () -> Unit = {},
    val onSaveBrowserOrigin: (String) -> Unit = {},
    val onCopyApiAddress: () -> Unit = {},
    val onPreviewOverlay: () -> Unit = {},
    val onOpenCaptions: () -> Unit = {},
)
