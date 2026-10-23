package com.sal7one.hearth.desktop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radii and spacing, in one place so a screen never invents a value. */
@Immutable
internal data class HearthShapes(
    val radius: Dp = 14.dp,
    val large: Dp = 22.dp,
    val xl: Dp = 28.dp,
    val pill: Dp = 999.dp,
    val control: Dp = 12.dp,
    val small: Dp = 8.dp,
    val tiny: Dp = 6.dp,
) {
    val base = RoundedCornerShape(radius)
    val card = RoundedCornerShape(large)
    val hero = RoundedCornerShape(xl)
    val controlShape = RoundedCornerShape(control)
    val smallShape = RoundedCornerShape(small)
    val pillShape = RoundedCornerShape(pill)
}

/** Spacing scale (dp). Layout uses these rather than ad-hoc numbers. */
internal object Space {
    val xxs: Dp = 4.dp
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 22.dp
    val xxl: Dp = 26.dp
    val section: Dp = 44.dp
    val pageTop: Dp = 34.dp
    val pageBottom: Dp = 96.dp
    val pageX: Dp = 26.dp
    val maxWidth: Dp = 1240.dp
    val sideColumn: Dp = 336.dp
    val railTopBar: Dp = 66.dp
}
