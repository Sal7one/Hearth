package com.sal7one.hearth.desktop.ui.screens.connections

import com.sal7one.hearth.desktop.ui.DesktopScreen

/**
 * Connection callbacks. Saving a connection writes the route for its stage; keys go
 * through the credential store and are never part of the exported configuration.
 */
internal class ConnectionsActions(
    val onNavigate: (DesktopScreen) -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onSave: (engineId: String, endpoint: String, model: String, credentialId: String) -> Unit =
        { _, _, _, _ -> },
    val onSaveKey: (credentialId: String, secret: String) -> Unit = { _, _ -> },
    val onForgetKey: (credentialId: String) -> Unit = {},
)
