package com.sal7one.hearth.desktop.ui.screens.overlay

/**
 * Overlay settings live in the same small string map the app already persists for
 * UI preferences. Unknown or corrupt values fall back to the default rather than
 * failing to open the window.
 */
internal fun overlaySettingsFrom(ui: Map<String, String>): OverlaySettings {
    val defaults = OverlaySettings()
    fun fraction(key: String, default: Float, low: Float, high: Float) =
        ui[key]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(low, high) ?: default
    val bounds = listOf("x", "y", "w", "h").map { ui["overlay-window-$it"]?.toIntOrNull() }
        .takeIf { it.all { value -> value != null } }?.map { checkNotNull(it) }?.let {
            if (it[0] !in -100_000..100_000 || it[1] !in -100_000..100_000 || it[2] !in 340..4000 || it[3] !in 90..720) null
            else OverlayBounds(it[0], it[1], it[2], it[3])
        }
    return OverlaySettings(
        position = ui["overlay-position"]?.let { stored -> OverlayPosition.entries.firstOrNull { it.name == stored } }
            ?: defaults.position,
        widthFraction = fraction("overlay-width", defaults.widthFraction, 0.25f, 1f),
        textSize = ui["overlay-text"]?.let { stored -> OverlayTextSize.entries.firstOrNull { it.name == stored } }
            ?: defaults.textSize,
        backdropFraction = fraction("overlay-backdrop", defaults.backdropFraction, 0f, 1f),
        showOriginal = ui["overlay-original"] != "false",
        showPartials = ui["overlay-partials"] != "false",
        clickThrough = ui["overlay-click-through"] == "true",
        style = ui["overlay-style"]?.let { stored -> OverlayStyle.entries.firstOrNull { it.name == stored } }
            ?: defaults.style,
        visible = false,
        pinnedDisplay = ui["overlay-display"].orEmpty().ifBlank { "auto" },
        bounds = bounds,
        textScale = fraction("overlay-scale", defaults.textScale, 0.65f, 2f),
        maxLines = ui["overlay-lines"]?.toIntOrNull()?.coerceIn(1, 6) ?: defaults.maxLines,
        textAlign = OverlayTextAlign.entries.firstOrNull { it.name == ui["overlay-align"] } ?: defaults.textAlign,
        shortcutsEnabled = ui["overlay-shortcuts"] != "false",
        toolbarScale = fraction("overlay-toolbar-scale", defaults.toolbarScale, 0.75f, 1.5f),
        shadeHoverArea = ui["overlay-hover-shade"] == "true",
        windowHeight = ui["overlay-height"]?.toIntOrNull()?.coerceIn(90, 720) ?: defaults.windowHeight,
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
    "overlay-display" to settings.pinnedDisplay,
    "overlay-scale" to settings.textScale.toString(),
    "overlay-lines" to settings.maxLines.toString(),
    "overlay-align" to settings.textAlign.name,
    "overlay-shortcuts" to settings.shortcutsEnabled.toString(),
    "overlay-toolbar-scale" to settings.toolbarScale.toString(),
    "overlay-hover-shade" to settings.shadeHoverArea.toString(),
    "overlay-height" to settings.windowHeight.toString(),
    "overlay-window-x" to settings.bounds?.x?.toString().orEmpty(),
    "overlay-window-y" to settings.bounds?.y?.toString().orEmpty(),
    "overlay-window-w" to settings.bounds?.width?.toString().orEmpty(),
    "overlay-window-h" to settings.bounds?.height?.toString().orEmpty(),
)
