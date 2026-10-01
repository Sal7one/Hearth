package com.sal7one.transiber.caption

import android.content.res.Configuration

/** Stored options can change live; capture source, pause and open controls belong to the session. */
internal fun CaptionOverlayConfig.withLiveOverlayState(live: CaptionOverlayConfig, source: CaptionSource?) = copy(
    source = source ?: live.source, paused = live.paused, tapThrough = live.tapThrough,
    showSettings = live.showSettings, languagePicker = live.languagePicker,
)

/** Rotation replaces persisted geometry without resetting the live session or controls. */
internal fun CaptionOverlayConfig.withStoredGeometry(stored: CaptionOverlayConfig) = copy(
    widthPercent = stored.widthPercent, maxHeightPercent = stored.maxHeightPercent,
    bubbleHeightDp = stored.bubbleHeightDp, anchor = stored.anchor,
    xOffsetPx = stored.xOffsetPx, yOffsetPx = stored.yOffsetPx,
)

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
    val PORTRAIT = OverlayGeometry(60, 40)
    val LANDSCAPE = OverlayGeometry(40, 60)

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

/** Preserve old reading size until explicitly resized; all panels then share one height. */
internal fun overlayHeightPx(viewportHeight: Int, density: Float, config: CaptionOverlayConfig): Int {
    val legacy = minOf(viewportHeight * config.maxHeightPercent / 100f, 320f * density)
    val reading = config.bubbleHeightDp?.times(density) ?: legacy
    val requested = if (config.showSettings && config.languagePicker != null) maxOf(reading, 440f * density) else reading
    return requested.coerceAtLeast(144f * density).toInt().coerceIn(1, viewportHeight.coerceAtLeast(1))
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
