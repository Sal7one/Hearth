package com.sal7one.transiber.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val HearthHeading = FontFamily.Serif
private val HearthBody = FontFamily.SansSerif

/**
 * Caprasimo/Figtree are represented by bundled platform families so the
 * typography is always available in the air-gapped app.
 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = HearthHeading,
        fontWeight = FontWeight.Black,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = HearthHeading,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 39.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = HearthHeading,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 35.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = HearthHeading,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = HearthHeading,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = HearthHeading,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = HearthBody,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.15.sp,
    ),
)
