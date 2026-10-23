package com.sal7one.hearth.desktop.ui.screens.integrations

import com.sal7one.hearth.desktop.DesktopUiState
import com.sal7one.hearth.desktop.WorkspaceInput
import com.sal7one.hearth.core.PipelineStatus

/** The loopback server as the page renders it. URLs are only real while running. */
internal data class IntegrationsState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val baseUrl: String = "",
    val overlayUrl: String = "",
    val limits: List<Pair<String, String>> = emptyList(),
    val browserOrigin: String = "",
    val captionStatus: String = "No captions started yet",
    val captionDetail: String = "Open Captions, choose your audio input and start listening. The connection alone does not capture sound.",
) {
    val overlayUrlDisplay: String
        get() = if (!running || overlayUrl.isBlank()) "Available after starting the connection" else overlayUrl.substringBefore("#") + "#…"
}

internal fun integrationsState(workspace: DesktopUiState, browserOrigin: String = ""): IntegrationsState {
    val view = workspace.integration
    return IntegrationsState(
        running = view != null,
        busy = workspace.integrationBusy,
        baseUrl = view?.baseUrl.orEmpty(),
        overlayUrl = view?.overlayUrl.orEmpty(),
        browserOrigin = browserOrigin,
        captionStatus = when {
            workspace.starting -> "Starting a session…"
            workspace.snapshot?.status == PipelineStatus.FAILED -> "Last session failed"
            workspace.active && workspace.input != WorkspaceInput.TEXT -> "Audio session running"
            workspace.active -> "Text session running"
            else -> "No active captions"
        },
        captionDetail = when {
            workspace.starting -> "Preparing the saved engines. The connection stays ready while the session loads."
            workspace.snapshot?.status == PipelineStatus.FAILED -> workspace.snapshot.error ?: "Open Captions to review the engine error and retry."
            workspace.active -> "OBS displays the latest shared session. Keep the audio source playing and check that caption text appears in Hearth."
            else -> "Open Captions, choose microphone or system audio, then start. OBS can show the last result until a new session produces text."
        },
        limits = listOf(
            "Work at once" to "Four external jobs",
            "Media upload" to "Up to 64 MiB",
            "Access" to "This computer only",
        ),
    )
}
