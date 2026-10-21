package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.art.HearthArt
import com.sal7one.hearth.desktop.ui.art.rememberArtwork
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/**
 * A desktop scene: window chrome, playing video, and the caption window over it.
 * The caption is the real [CaptionLayer], positioned by the real settings, so the
 * preview cannot show something the floating window would not.
 */
@Composable
internal fun OverlayScene(
    state: OverlayState,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val artwork = rememberArtwork(HearthArt.CAPTIONS)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(22.dp)),
    ) {
        SceneHeader()
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, -0.52f),
                    alpha = 0.45f,
                )
            } else {
                Box(Modifier.fillMaxSize().background(colors.surface3))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.10f),
                                Color.Black.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            )

            LiveBadge(
                text = "${OverlayCopy.LIVE} · preview",
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = when (state.settings.position) {
                    OverlayPosition.TOP -> Alignment.TopCenter
                    OverlayPosition.CENTER -> Alignment.Center
                    OverlayPosition.BOTTOM -> Alignment.BottomCenter
                },
            ) {
                CaptionLayer(
                    state = state,
                    modifier = Modifier.fillMaxWidth(state.settings.widthFraction.coerceIn(0.3f, 1f)),
                    scale = 0.92f,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}
