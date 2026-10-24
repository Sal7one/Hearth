package com.sal7one.transiber.caption

import android.content.res.Configuration

/** Stored options can change live; capture source, pause and open controls belong to the session. */
internal fun CaptionOverlayConfig.withLiveOverlayState(live: CaptionOverlayConfig, source: CaptionSource?) = copy(
    source = source ?: live.source, paused = live.paused, tapThrough = live.tapThrough,
    showSettings = live.showSettings, languagePicker = live.languagePicker,
    minimalHeaderExpanded = minimalHeader && live.minimalHeader && live.minimalHeaderExpanded,
)

/** Rotation replaces persisted geometry without resetting the live session or controls. */
internal fun CaptionOverlayConfig.withStoredGeometry(stored: CaptionOverlayConfig) = copy(
    widthPercent = stored.widthPercent, maxHeightPercent = stored.maxHeightPercent,
    bubbleHeightDp = stored.bubbleHeightDp, anchor = stored.anchor,
    minimalHeightDp = stored.minimalHeightDp,
    xOffsetPx = stored.xOffsetPx, yOffsetPx = stored.yOffsetPx,
)

/** Reset presentation only; selected engines, languages and the live session survive. */
internal fun CaptionOverlayConfig.resetOverlayPresentation(orientation: OverlayOrientation, television: Boolean = false): CaptionOverlayConfig {
    val defaults = OverlayGeometryDefaults.defaultsFor(orientation)
    return copy(tvSubtitleMode = if (television) true else tvSubtitleMode, widthPercent = defaults.widthPercent, maxHeightPercent = defaults.maxHeightPercent,
        bubbleHeightDp = null, anchor = CaptionAnchor.BOTTOM, xOffsetPx = 0, yOffsetPx = 0,
        minimalHeader = false, minimalHeightDp = MINIMAL_OVERLAY_DEFAULT_HEIGHT_DP, minimalHeaderExpanded = false,
        fontScale = CaptionFontScale.NORMAL, historyLines = CaptionReading.DEFAULT_PREVIOUS_LINES,
        showPartial = true, theme = CaptionTheme.DARK, backgroundOpacity = 70,
        textColorArgb = null, textOpacity = 100,
        tapThrough = false, languagePicker = null)
}

/** Main-thread appearance drafts protect live size and text from older DataStore emissions.
 *  Only a completed gesture writes settings; a late commit cannot clear a newer draft. */
internal class OverlaySizePreview {
    private var draft: CaptionOverlayConfig? = null
    private var revision = 0L
    fun preview(config: CaptionOverlayConfig) { draft = config; revision++ }
    fun applyTo(stored: CaptionOverlayConfig): CaptionOverlayConfig = draft?.let {
        stored.copy(widthPercent = it.widthPercent, bubbleHeightDp = it.bubbleHeightDp,
            minimalHeightDp = it.minimalHeightDp, textColorArgb = it.textColorArgb, textOpacity = it.textOpacity)
    } ?: stored
    fun beginCommit(config: CaptionOverlayConfig): Long {
        if (draft != null) draft = config
        return ++revision
    }
    fun finishCommit(token: Long) { if (token == revision) draft = null }
    fun cancel() { draft = null; revision++ }
}

/** Geometry is remembered per orientation: a bubble tuned for a fullscreen video is
 *  not the bubble you want over a portrait feed, and vice versa. */
internal enum class OverlayOrientation { PORTRAIT, LANDSCAPE;

    companion object {
        fun from(configurationOrientation: Int): OverlayOrientation =
            if (configurationOrientation == Configuration.ORIENTATION_LANDSCAPE) LANDSCAPE else PORTRAIT
    }
}

/** Orientation-specific bubble defaults; legacy installs keep their stored portrait values. */
internal object OverlayGeometryDefaults {
    val PORTRAIT = OverlayGeometry(85, 40)
    val LANDSCAPE = OverlayGeometry(80, 30)

    data class OverlayGeometry internal constructor(val widthPercent: Int, val maxHeightPercent: Int)
    fun defaultsFor(orientation: OverlayOrientation) = if (orientation == OverlayOrientation.LANDSCAPE) LANDSCAPE else PORTRAIT
}

/** Physical display coordinates; every edge includes bars/cutouts and a grab margin. */
internal data class OverlayViewport(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(1)
    val height get() = (bottom - top).coerceAtLeast(1)
}

internal data class OverlayPlacement(val x: Int, val y: Int, val width: Int, val height: Int)

/** Use the actual measured window, not an assumed visible strip, for all anchors. */
internal fun placeOverlay(
    viewport: OverlayViewport,
    width: Int,
    height: Int,
    anchor: CaptionAnchor,
    dx: Int,
    dy: Int,
): OverlayPlacement {
    val w = width.coerceIn(1, viewport.width)
    val h = height.coerceIn(1, viewport.height)
    val baseX = viewport.left + (viewport.width - w) / 2
    val baseY = when (anchor) {
        CaptionAnchor.TOP -> viewport.top
        CaptionAnchor.CENTER -> viewport.top + (viewport.height - h) / 2
        CaptionAnchor.BOTTOM -> viewport.bottom - h
    }
    val yDelta = if (anchor == CaptionAnchor.BOTTOM) -dy.toLong() else dy.toLong()
    return OverlayPlacement(
        (baseX.toLong() + dx).coerceIn(viewport.left.toLong(), (viewport.right - w).toLong()).toInt(),
        (baseY.toLong() + yDelta).coerceIn(viewport.top.toLong(), (viewport.bottom - h).toLong()).toInt(),
        w, h,
    )
}

/** TV has no inline controls, so one caption line can use a much smaller viewport. */
internal fun minimumOverlayHeightDp(television: Boolean) = if (television) 32 else 144
internal const val TV_OVERLAY_DEFAULT_HEIGHT_DP = 64
internal const val MINIMAL_OVERLAY_DEFAULT_HEIGHT_DP = 64
internal const val MINIMAL_OVERLAY_MIN_HEIGHT_DP = 48
internal const val MINIMAL_OVERLAY_CHROME_HEIGHT_DP = 96

/** Sliders/buttons edit the saved reading area, never temporary controls/settings chrome. */
internal fun CaptionOverlayConfig.withActiveOverlayHeightDp(height: Int, television: Boolean = false): CaptionOverlayConfig =
    if (minimalHeader && !television) copy(minimalHeightDp = height.coerceIn(MINIMAL_OVERLAY_MIN_HEIGHT_DP, 600))
    else copy(bubbleHeightDp = height.coerceIn(minimumOverlayHeightDp(television), 600))

/** A fixed user-selected viewport: long text scrolls inside it, never grows the window. */
internal fun overlayHeightPx(viewportHeight: Int, density: Float, config: CaptionOverlayConfig, television: Boolean = false): Int {
    val legacy = if (television) TV_OVERLAY_DEFAULT_HEIGHT_DP * density
        else minOf(viewportHeight * config.maxHeightPercent / 100f, 320f * density)
    val reading = config.bubbleHeightDp?.times(density) ?: legacy
    if (config.minimalHeader && !television) {
        val compact = config.minimalHeightDp.coerceIn(MINIMAL_OVERLAY_MIN_HEIGHT_DP, 600) * density
        val height = when {
            config.showSettings -> reading.coerceAtLeast(240 * density)
            config.minimalHeaderExpanded && !config.tapThrough ->
                (compact + MINIMAL_OVERLAY_CHROME_HEIGHT_DP * density).coerceAtLeast(144 * density)
            else -> compact
        }
        return height.toInt().coerceIn(1, viewportHeight.coerceAtLeast(1))
    }
    return reading.coerceAtLeast(minimumOverlayHeightDp(television) * density).toInt().coerceIn(1, viewportHeight.coerceAtLeast(1))
}

/** Prefer outside the captions; even a full-screen or dragged bubble must retain a reachable handle. */
internal fun placeTapThroughHandle(viewport: OverlayViewport, bubble: OverlayPlacement, size: Int, gap: Int): OverlayPlacement {
    val w = size.coerceIn(1, viewport.width)
    val h = size.coerceIn(1, viewport.height)
    val y = when {
        bubble.y - gap - h >= viewport.top -> bubble.y - gap - h
        bubble.y + bubble.height + gap + h <= viewport.bottom -> bubble.y + bubble.height + gap
        else -> bubble.y
    }
    return OverlayPlacement(
        (bubble.x + bubble.width - w).coerceIn(viewport.left, viewport.right - w),
        y.coerceIn(viewport.top, viewport.bottom - h), w, h,
    )
}

/** Remote movement uses screen directions, including the bottom anchor's inverted offset. */
internal fun CaptionOverlayConfig.nudgeOverlay(
    viewport: OverlayViewport, density: Float, dx: Int, dy: Int, television: Boolean = false,
): CaptionOverlayConfig {
    val height = overlayHeightPx(viewport.height, density, this, television)
    val origin = placeOverlay(viewport, viewport.width * widthPercent / 100, height, anchor, 0, 0)
    val current = placeOverlay(viewport, origin.width, height, anchor, xOffsetPx, yOffsetPx)
    val x = (current.x.toLong() + dx).coerceIn(viewport.left.toLong(), (viewport.right - origin.width).toLong()).toInt()
    val y = (current.y.toLong() + dy).coerceIn(viewport.top.toLong(), (viewport.bottom - height).toLong()).toInt()
    return copy(xOffsetPx = x - origin.x, yOffsetPx = if (anchor == CaptionAnchor.BOTTOM) origin.y - y else y - origin.y)
}
