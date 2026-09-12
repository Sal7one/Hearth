package com.sal7one.transiber.caption

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
