package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.sal7one.hearth.desktop.ui.art.HearthArt
import com.sal7one.hearth.desktop.ui.art.rememberArtwork
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/**
 * Artwork plus the fade that lets a caption sit on it. When a resource fails to
 * decode we fall back to a tonal wash rather than an empty hole — a tile is still
 * legible without its illustration.
 */
@Composable
internal fun TileArtwork(
    art: HearthArt?,
    modifier: Modifier = Modifier,
    scrimToSurface: Boolean = true,
) {
    val colors = HearthTheme.colors
    val bitmap = art?.let { rememberArtwork(it) }
    Box(modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = TileScrim.IMAGE_ALPHA,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.accent.copy(alpha = 0.16f),
                                colors.surface3,
                                colors.surface2,
                            ),
                        ),
                    ),
            )
        }
        if (scrimToSurface) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                TileScrim.TOP_FADE to Color.Transparent,
                                TileScrim.BOTTOM_FADE to colors.surface.copy(alpha = 0.86f),
                                1f to colors.surface,
                            ),
                        ),
                    ),
            )
        }
    }
}
