package com.sal7one.transiber.reading

/** Palettes change only regular-box colours; geometry and sampled replacement stay independent. */
internal enum class ReadingBoxPreset(val background: Int, val foreground: Int, val opacity: Int) {
    LIGHT(0xfff8fafc.toInt(), 0xff1f2937.toInt(), 95),
    DARK(0xff17212b.toInt(), 0xfff1f5f9.toInt(), 95),
    WARM(0xfffff1cf.toInt(), 0xff493521.toInt(), 95),
    HIGH_CONTRAST(0xff000000.toInt(), 0xffffffff.toInt(), 100);

    fun applyTo(settings: ReadingOverlaySettings) = settings.copy(
        boxBackgroundArgb = background, boxTextArgb = foreground, boxOpacity = opacity,
    ).bounded()

    fun matches(settings: ReadingOverlaySettings): Boolean = settings.boxBackgroundArgb == background &&
        settings.boxTextArgb == foreground && settings.boxOpacity == opacity
}

/** Apply a style snapshot without overwriting newer capture, motion, or display preferences. */
internal fun ReadingOverlaySettings.withReadingAppearance(style: ReadingOverlaySettings) = copy(
    replaceText = style.replaceText,
    boxWidthPercent = style.boxWidthPercent,
    boxHeightPercent = style.boxHeightPercent,
    boxBackgroundArgb = style.boxBackgroundArgb,
    boxTextArgb = style.boxTextArgb,
    boxOpacity = style.boxOpacity,
).bounded()

internal val readingBoxColourChoices = listOf(
    0xff000000.toInt(), 0xffffffff.toInt(), 0xff17212b.toInt(),
    0xfffff1cf.toInt(), 0xff55e0cb.toInt(), 0xfff9bf60.toInt(),
)

internal fun readingRgbChannel(colour: Int, shift: Int): Int = (colour ushr shift) and 255
internal fun readingWithRgbChannel(colour: Int, shift: Int, value: Int): Int =
    ((colour and (255 shl shift).inv()) or (value.coerceIn(0, 255) shl shift)) or 0xff000000.toInt()
