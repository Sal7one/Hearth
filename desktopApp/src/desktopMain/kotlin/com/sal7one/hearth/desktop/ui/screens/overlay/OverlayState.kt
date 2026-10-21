package com.sal7one.hearth.desktop.ui.screens.overlay

/** Where the caption line sits inside the floating window. */
internal enum class OverlayPosition(val label: String) { TOP("Top"), CENTER("Center"), BOTTOM("Bottom") }

internal enum class OverlayTextSize(val label: String, val baseSp: Float) {
    SMALL("S", 15f),
    MEDIUM("M", 18f),
    LARGE("L", 22f),
    HUGE("XL", 26f),
}

internal enum class OverlayStyle(val label: String, val detail: String) {
    GLASS("Glass", "Soft panel with blur"),
    BAR("Cinema bar", "Solid black strip"),
    CLEAR("Clear", "Shadowed text only"),
}

/**
 * The persisted half of the overlay: exactly what the floating window reads.
 * The settings page and the window share this object, so the preview cannot drift
 * from what is actually on screen.
 */
internal data class OverlaySettings(
    val position: OverlayPosition = OverlayPosition.BOTTOM,
    val widthFraction: Float = 0.76f,
    val textSize: OverlayTextSize = OverlayTextSize.MEDIUM,
    val backdropFraction: Float = 0.74f,
    val showOriginal: Boolean = true,
    val showPartials: Boolean = true,
    val clickThrough: Boolean = false,
    val style: OverlayStyle = OverlayStyle.GLASS,
    val visible: Boolean = false,
    val pinnedDisplay: String = "Follow the window",
)

/** The caption currently shown, already resolved from the session. */
internal data class OverlayCaptionView(
    val primary: String,
    val original: String?,
    val partial: Boolean,
)

/** Settings plus the live caption. The screen and the window both render from this. */
internal data class OverlayState(
    val settings: OverlaySettings = OverlaySettings(),
    val caption: OverlayCaptionView? = null,
    val targetRtl: Boolean = false,
    val translationEnabled: Boolean = false,
) {
    val widthPercentLabel: String get() = "${(settings.widthFraction * 100).toInt()}%"
    val backdropPercentLabel: String get() = "${(settings.backdropFraction * 100).toInt()}%"
    val hasCaption: Boolean get() = caption != null
}
