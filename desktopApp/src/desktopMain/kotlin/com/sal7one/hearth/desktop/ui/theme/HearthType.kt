package com.sal7one.hearth.desktop.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * One sans stack, one scale. The mockup uses Inter and falls back to the system
 * face; the desktop build uses the platform UI font so text metrics match the OS.
 *
 * Tracking tightens as size grows: display and h1 carry -0.03em, body is neutral.
 */
internal object HearthType {
    val family = FontFamily.SansSerif
    val mono = FontFamily.Monospace

    private val tight = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

    val display = TextStyle(
        fontFamily = family, fontSize = 46.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1.5).sp, lineHeightStyle = tight,
    )
    val h1 = TextStyle(
        fontFamily = family, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.96).sp,
    )
    val h2 = TextStyle(
        fontFamily = family, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.38).sp,
    )
    val h3 = TextStyle(
        fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.16).sp,
    )
    val body = TextStyle(
        fontFamily = family, fontSize = 14.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val lede = TextStyle(
        fontFamily = family, fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp,
    )
    val small = TextStyle(
        fontFamily = family, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val micro = TextStyle(
        fontFamily = family, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.43.sp,
    )
    val label = TextStyle(
        fontFamily = family, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
        letterSpacing = (-0.05).sp,
    )
    val rowTitle = TextStyle(
        fontFamily = family, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
        letterSpacing = (-0.05).sp,
    )
    val rowSub = TextStyle(
        fontFamily = family, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val monoSmall = TextStyle(
        fontFamily = mono, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val transcriptOriginal = TextStyle(
        fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val transcriptTranslation = TextStyle(
        fontFamily = family, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
        letterSpacing = (-0.55).sp,
    )
}
