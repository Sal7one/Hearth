package com.sal7one.hearth.desktop.ui.screens.connections

import com.sal7one.hearth.core.PipelineStage

/**
 * One cloud engine. It is only editable while it is the saved route for its stage —
 * otherwise typing an endpoint here would silently replace a working local engine.
 */
internal data class ConnectionRow(
    val engineId: String,
    val name: String,
    val stage: PipelineStage,
    val stageLabel: String,
    val selected: Boolean,
    val endpoint: String,
    val model: String,
    val credentialId: String,
    val available: Boolean,
    val unavailableReason: String? = null,
) {
    val configured: Boolean get() = selected && endpoint.isNotBlank()
    val hasCredential: Boolean get() = credentialId.isNotBlank()
}

internal data class ConnectionsState(
    val rows: List<ConnectionRow> = emptyList(),
    val credentialStorage: String = "the operating system secure store",
) {
    val configuredCount: Int get() = rows.count { it.selected }
}
