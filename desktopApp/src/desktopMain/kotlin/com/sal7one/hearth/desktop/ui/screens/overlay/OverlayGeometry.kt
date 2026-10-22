package com.sal7one.hearth.desktop.ui.screens.overlay

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.max
import kotlin.math.min

/** AWT user-space pixels; coordinates can be negative on a secondary display. */
internal data class OverlayBounds(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class OverlayDisplay(val id: String, val name: String, val area: OverlayBounds)
internal enum class ResizeEdge(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    LEFT(left = true), RIGHT(right = true), TOP(top = true), BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true), TOP_RIGHT(top = true, right = true),
    BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true)
}

internal fun overlayDisplays(): List<OverlayDisplay> {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = environment.defaultScreenDevice
    return environment.screenDevices.sortedBy { if (it == primary) 0 else 1 }.mapIndexed { index, device ->
        val config = device.defaultConfiguration
        val rect = config.bounds
        val inset = Toolkit.getDefaultToolkit().getScreenInsets(config)
        OverlayDisplay(device.iDstring, "Display ${index + 1} · ${rect.width} × ${rect.height}", OverlayBounds(
            rect.x + inset.left, rect.y + inset.top,
            max(1, rect.width - inset.left - inset.right), max(1, rect.height - inset.top - inset.bottom)))
    }
}

internal fun overlayDisplayFor(bounds: OverlayBounds?, displays: List<OverlayDisplay>, pinned: String = "auto"): OverlayDisplay {
    require(displays.isNotEmpty()) { "No display is available for captions" }
    displays.firstOrNull { it.id == pinned }?.let { return it }
    return displays.maxBy { display ->
        if (bounds == null) 0L else {
            val area = display.area
            max(0, min(bounds.x + bounds.width, area.x + area.width) - max(bounds.x, area.x)).toLong() *
                max(0, min(bounds.y + bounds.height, area.y + area.height) - max(bounds.y, area.y))
        }
    }
}

internal fun clampOverlayBounds(bounds: OverlayBounds, area: OverlayBounds): OverlayBounds {
    val width = bounds.width.coerceIn(min(340, area.width), min(4000, area.width))
    val height = bounds.height.coerceIn(min(90, area.height), min(720, area.height))
    return OverlayBounds(bounds.x.coerceIn(area.x, area.x + area.width - width),
        bounds.y.coerceIn(area.y, area.y + area.height - height), width, height)
}

internal fun anchoredOverlayBounds(settings: OverlaySettings, display: OverlayDisplay): OverlayBounds {
    val area = display.area
    val width = (area.width * settings.widthFraction.coerceIn(0.25f, 1f)).toInt().coerceAtLeast(340)
    val height = settings.bounds?.height ?: settings.windowHeight
    val y = when (settings.position) {
        OverlayPosition.TOP -> area.y + 28
        OverlayPosition.CENTER -> area.y + (area.height - height) / 2
        OverlayPosition.BOTTOM -> area.y + area.height - height - 40
    }
    return clampOverlayBounds(OverlayBounds(area.x + (area.width - width) / 2, y, width, height), area)
}

internal fun initialOverlayBounds(settings: OverlaySettings, displays: List<OverlayDisplay>): OverlayBounds {
    val display = overlayDisplayFor(settings.bounds, displays, settings.pinnedDisplay)
    return settings.bounds?.let { clampOverlayBounds(it, display.area) } ?: anchoredOverlayBounds(settings, display)
}

internal fun resizeOverlayBounds(start: OverlayBounds, dx: Int, dy: Int, edge: ResizeEdge): OverlayBounds {
    val width = (start.width + if (edge.left) -dx else if (edge.right) dx else 0).coerceIn(340, 4000)
    val height = (start.height + if (edge.top) -dy else if (edge.bottom) dy else 0).coerceIn(90, 720)
    return OverlayBounds(if (edge.left) start.x + start.width - width else start.x,
        if (edge.top) start.y + start.height - height else start.y, width, height)
}

/** Keep the bottom edge stable when changing the height from either control surface. */
internal fun overlayWithHeight(settings: OverlaySettings, height: Int): OverlaySettings {
    val safe = height.coerceIn(90, 720)
    return settings.copy(windowHeight = safe, bounds = settings.bounds?.let {
        it.copy(y = it.y + it.height - safe, height = safe)
    })
}

/** Width adjustments preserve the manual center instead of resetting placement. */
internal fun overlayWithWidth(settings: OverlaySettings, fraction: Float, displays: List<OverlayDisplay>): OverlaySettings {
    val safe = fraction.coerceIn(0.25f, 1f)
    val bounds = settings.bounds
    if (bounds == null || displays.isEmpty()) return settings.copy(widthFraction = safe, bounds = null)
    val display = overlayDisplayFor(bounds, displays, settings.pinnedDisplay)
    val width = (display.area.width * safe).toInt().coerceAtLeast(340)
    return settings.copy(widthFraction = safe, bounds = clampOverlayBounds(
        bounds.copy(x = bounds.x + (bounds.width - width) / 2, width = width), display.area))
}
