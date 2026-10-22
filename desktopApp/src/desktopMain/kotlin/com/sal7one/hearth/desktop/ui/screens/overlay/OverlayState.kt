package com.sal7one.hearth.desktop.ui.screens.overlay

/** Where a reset or anchored caption window sits on the chosen display. */
internal enum class OverlayPosition(val label: String) { TOP("Top"), CENTER("Center"), BOTTOM("Bottom") }

internal enum class OverlayTextSize(val label: String, val baseSp: Float) {
    SMALL("S", 18f),
    MEDIUM("M", 24f),
    LARGE("L", 30f),
    HUGE("XL", 38f),
}

internal enum class OverlayStyle(val label: String, val detail: String) {
    GLASS("Soft panel", "A compact, translucent background"),
    BAR("Cinema bar", "Solid black strip"),
    CLEAR("Subtitles", "Clean text with a strong shadow"),
}

internal enum class OverlayTextAlign(val label: String) { CENTER("Center"), START("Reading direction") }

/**
 * The persisted half of the overlay: exactly what the floating window reads.
 * The settings page and the window share this object, so the preview cannot drift
 * from what is actually on screen.
 */
internal data class OverlaySettings(
    val position: OverlayPosition = OverlayPosition.BOTTOM,
    val widthFraction: Float = 0.64f,
    val textSize: OverlayTextSize = OverlayTextSize.MEDIUM,
    val backdropFraction: Float = 0.56f,
    val showOriginal: Boolean = true,
    val showPartials: Boolean = true,
    val clickThrough: Boolean = false,
    val style: OverlayStyle = OverlayStyle.CLEAR,
    val visible: Boolean = false,
    val pinnedDisplay: String = "auto",
    val bounds: OverlayBounds? = null,
    val textScale: Float = 1f,
    val maxLines: Int = 3,
    val textAlign: OverlayTextAlign = OverlayTextAlign.CENTER,
    val shortcutsEnabled: Boolean = true,
    val toolbarScale: Float = 1f,
    val shadeHoverArea: Boolean = false,
    val windowHeight: Int = 190,
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
    val shortcutStatus: String = "System shortcuts are starting…",
    val nativeControlsAvailable: Boolean = false,
    val controlIssue: String? = null,
) {
    val widthPercentLabel: String get() = "${(settings.widthFraction * 100).toInt()}%"
    val backdropPercentLabel: String get() = "${(settings.backdropFraction * 100).toInt()}%"
    val hasCaption: Boolean get() = caption != null
}
