package com.sal7one.hearth.desktop.ui.screens.integrations

import com.sal7one.hearth.desktop.DesktopUiState

/** The loopback server as the page renders it. URLs are only real while running. */
internal data class IntegrationsState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val baseUrl: String = "",
    val overlayUrl: String = "",
    val limits: List<Pair<String, String>> = emptyList(),
) {
    val overlayUrlDisplay: String
        get() = if (overlayUrl.isBlank()) "Not running" else overlayUrl.substringBefore("#") + "#…"
}

internal fun integrationsState(workspace: DesktopUiState): IntegrationsState {
    val view = workspace.integration
    return IntegrationsState(
        running = view != null,
        busy = workspace.integrationBusy,
        baseUrl = view?.baseUrl.orEmpty(),
        overlayUrl = view?.overlayUrl.orEmpty(),
        limits = listOf(
            "Concurrency" to "Four sessions",
            "Size" to "64 MiB per file",
            "Access" to "This Mac only",
        ),
    )
}
