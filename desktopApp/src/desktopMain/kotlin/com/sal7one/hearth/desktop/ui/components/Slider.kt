package com.sal7one.hearth.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/**
 * A thin track with a round thumb. The value updates continuously (a live preview
 * needs no Apply step) and the caller owns commit-on-release if it wants one.
 */
@Composable
internal fun HearthSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val updateValue by rememberUpdatedState(onValueChange)
    val colors = HearthTheme.colors
    val density = LocalDensity.current
    val thumb = 16.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    updateValue((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { pointer, _ ->
                    updateValue((pointer.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(colors.surface3, RoundedCornerShape(99.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(4.dp)
                .background(if (enabled) colors.accent else colors.faint, RoundedCornerShape(99.dp)),
        )
        val travelPx = with(density) { (maxWidth - thumb).toPx() }
        Box(
            Modifier
                .offset(x = with(density) { (travelPx * value.coerceIn(0f, 1f)).toDp() })
                .size(thumb)
                .background(if (enabled) colors.text else colors.muted, CircleShape),
        )
    }
}
