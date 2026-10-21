package com.sal7one.hearth.desktop.ui.screens.overlay

/**
 * The overlay page's callbacks. Settings changes are a single immutable bundle so
 * the page cannot produce a half-applied style (for example a bar with a radius).
 */
internal class OverlayActions(
    val onChange: (OverlaySettings) -> Unit = {},
    val onToggleVisible: (Boolean) -> Unit = {},
    val onReset: () -> Unit = {},
)
