package com.sal7one.hearth.desktop.ui.screens.overlay

/**
 * Overlay settings live in the same small string map the app already persists for
 * UI preferences. Unknown or corrupt values fall back to the default rather than
 * failing to open the window.
 */
internal fun overlaySettingsFrom(ui: Map<String, String>): OverlaySettings {
    val defaults = OverlaySettings()
    return OverlaySettings(
        position = ui["overlay-position"]?.let { stored -> OverlayPosition.entries.firstOrNull { it.name == stored } }
            ?: defaults.position,
        widthFraction = ui["overlay-width"]?.toFloatOrNull()?.coerceIn(0.4f, 1f) ?: defaults.widthFraction,
        textSize = ui["overlay-text"]?.let { stored -> OverlayTextSize.entries.firstOrNull { it.name == stored } }
            ?: defaults.textSize,
        backdropFraction = ui["overlay-backdrop"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: defaults.backdropFraction,
        showOriginal = ui["overlay-original"] != "false",
        showPartials = ui["overlay-partials"] != "false",
        clickThrough = ui["overlay-click-through"] == "true",
        style = ui["overlay-style"]?.let { stored -> OverlayStyle.entries.firstOrNull { it.name == stored } }
            ?: defaults.style,
        visible = false,
    )
}

/** The persisted projection of [OverlaySettings]. Visibility is intentionally not saved. */
internal fun overlayPrefs(settings: OverlaySettings): Map<String, String> = mapOf(
    "overlay-position" to settings.position.name,
    "overlay-width" to settings.widthFraction.toString(),
    "overlay-text" to settings.textSize.name,
    "overlay-backdrop" to settings.backdropFraction.toString(),
    "overlay-original" to settings.showOriginal.toString(),
    "overlay-partials" to settings.showPartials.toString(),
    "overlay-click-through" to settings.clickThrough.toString(),
    "overlay-style" to settings.style.name,
)
