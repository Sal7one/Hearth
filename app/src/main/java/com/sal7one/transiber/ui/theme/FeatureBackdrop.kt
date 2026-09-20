package com.sal7one.transiber.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

internal val LocalColorfulUi = staticCompositionLocalOf { true }
internal val LocalFeatureTint = staticCompositionLocalOf { Color(0xFF598EFF) }

/** Static local artwork only is blurred. Text, camera previews and controls stay sharp. */
@Composable
internal fun FeatureBackdrop(
    artwork: Int? = null, tint: Color = Color(0xFF598EFF), content: @Composable () -> Unit,
) {
    val vivid = LocalColorfulUi.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val ground = MaterialTheme.colorScheme.background
    CompositionLocalProvider(LocalFeatureTint provides tint, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(ground)) {
            if (vivid) {
                if (artwork != null) Image(painterResource(artwork), null,
                    modifier = Modifier.matchParentSize().blur(48.dp), contentScale = ContentScale.Crop,
                    alpha = if(dark) .35f else .16f)
                Box(Modifier.matchParentSize().drawWithCache {
                    val top = Brush.radialGradient(listOf(tint.copy(alpha = if(dark) .40f else .23f), Color.Transparent),
                        center = Offset(size.width * .05f, size.height * .22f), radius = size.width * 1.1f)
                    val bottom = Brush.radialGradient(listOf(Color(0xFFAD62FF).copy(alpha = if(dark) .33f else .17f), Color.Transparent),
                        center = Offset(size.width, size.height * .85f), radius = size.width * 1.2f)
                    onDrawBehind { drawRect(top); drawRect(bottom) }
                })
            }
            content()
        }
    }
}

/** Translucent colored panels sit over the already blurred backdrop, with a quiet lit edge. */
@Composable
internal fun Modifier.glassPanel(tint: Color = LocalFeatureTint.current, radius: Int = 24): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val surface = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(radius.dp)
    return if (!LocalColorfulUi.current) clip(shape).background(surface)
    else clip(shape).background(Brush.linearGradient(listOf(
        tint.copy(alpha = if (dark) .38f else .17f), surface.copy(alpha = .82f))))
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if(dark) .22f else .75f), tint.copy(alpha=.16f))), shape)
}
