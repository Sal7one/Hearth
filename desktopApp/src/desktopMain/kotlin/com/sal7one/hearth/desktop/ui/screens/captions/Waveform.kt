package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.theme.LocalReduceMotion

/**
 * Five bars that move only while something is actually listening. With reduce
 * motion on they hold a fixed height rather than stopping halfway.
 */
@Composable
internal fun Waveform(
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    bars: Int = 5,
) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "waveform")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
        repeat(bars) { index ->
            val animated by transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 620, delayMillis = index * 90, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            val fraction = when {
                !active -> 0.28f
                reduceMotion -> 0.6f
                else -> animated
            }
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(16.dp * fraction)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}
