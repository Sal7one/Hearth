package com.sal7one.transiber.caption

/**
 * A named, tappable collection of caption layout/text values. Applying a preset
 * copies only the "shape" fields of [CaptionOverlayConfig] — anchor, width,
 * offsets, font scale, line counts, opacity and touch behaviour — so the
 * user's chosen [CaptionTheme] (and everything else: mode, engine, target,
 * display) is preserved exactly as-is.
 *
 * [matches] and [CaptionPresets.selected] compare the same shape fields only,
 * so selection detection is theme-agnostic: a preset stays "selected" no matter
 * which of the four overlay themes is active.
 */
data class CaptionPreset(
    val id: String,
    val label: String,
    val description: String,
    val anchor: CaptionAnchor,
    val widthPercent: Int,
    val fontScale: CaptionFontScale,
    val historyLines: Int,
    val xOffsetPx: Int = 0,
    val yOffsetPx: Int = 0,
    val showPartial: Boolean = true,
    val backgroundOpacity: Int = 70,
    val tapThrough: Boolean = false,
    val paused: Boolean = false,
) {
    /** Returns a copy of [config] with only the shape fields overridden. */
    fun applyTo(config: CaptionOverlayConfig): CaptionOverlayConfig = config.copy(
        anchor = anchor,
        widthPercent = widthPercent,
        xOffsetPx = xOffsetPx,
        yOffsetPx = yOffsetPx,
        fontScale = fontScale,
        historyLines = historyLines,
        showPartial = showPartial,
        backgroundOpacity = backgroundOpacity,
        tapThrough = tapThrough,
        paused = paused,
    )

    /** True when [config]'s shape fields all equal this preset (theme ignored). */
    fun matches(config: CaptionOverlayConfig): Boolean =
        config.anchor == anchor &&
            config.widthPercent == widthPercent &&
            config.xOffsetPx == xOffsetPx &&
            config.yOffsetPx == yOffsetPx &&
            config.fontScale == fontScale &&
            config.historyLines == historyLines &&
            config.showPartial == showPartial &&
            config.backgroundOpacity == backgroundOpacity &&
            config.tapThrough == tapThrough &&
            config.paused == paused
}

/**
 * The predefined caption shapes offered in the overlay settings panel. Values
 * are composed purely from the existing [CaptionOverlayConfig] fields; no new
 * persisted state is introduced.
 */
object CaptionPresets {

    val BottomStrip = CaptionPreset(
        id = "bottom_strip",
        label = "Bottom strip",
        description = "Captions across the lower edge with recent context.",
        anchor = CaptionAnchor.BOTTOM,
        widthPercent = 90,
        fontScale = CaptionFontScale.NORMAL,
        historyLines = 2,
    )

    val TopTicker = CaptionPreset(
        id = "top_ticker",
        label = "Top ticker",
        description = "Compact single line pinned to the top edge.",
        anchor = CaptionAnchor.TOP,
        widthPercent = 100,
        fontScale = CaptionFontScale.SMALL,
        historyLines = 0,
    )

    val CenterFocus = CaptionPreset(
        id = "center_focus",
        label = "Center focus",
        description = "Focused captions centered on the screen.",
        anchor = CaptionAnchor.CENTER,
        widthPercent = 70,
        fontScale = CaptionFontScale.NORMAL,
        historyLines = 0,
    )

    val Theater = CaptionPreset(
        id = "theater",
        label = "Theater",
        description = "Full-width cinema captions with more history.",
        anchor = CaptionAnchor.BOTTOM,
        widthPercent = 100,
        fontScale = CaptionFontScale.NORMAL,
        historyLines = 4,
        backgroundOpacity = 85,
    )

    val Reading = CaptionPreset(
        id = "reading",
        label = "Reading",
        description = "Large text on a fully opaque background.",
        anchor = CaptionAnchor.BOTTOM,
        widthPercent = 90,
        fontScale = CaptionFontScale.LARGE,
        historyLines = 1,
        backgroundOpacity = 100,
    )

    val SubtleChip = CaptionPreset(
        id = "subtle_chip",
        label = "Subtle chip",
        description = "A minimal, mostly transparent caption.",
        anchor = CaptionAnchor.BOTTOM,
        widthPercent = 70,
        fontScale = CaptionFontScale.NORMAL,
        historyLines = 0,
        backgroundOpacity = 40,
    )

    /** All presets in display order. */
    val ALL: List<CaptionPreset> = listOf(
        BottomStrip,
        TopTicker,
        CenterFocus,
        Theater,
        Reading,
        SubtleChip,
    )

    /** The preset whose shape matches [config], or null when none does. */
    fun selected(config: CaptionOverlayConfig): CaptionPreset? =
        ALL.firstOrNull { it.matches(config) }
}
