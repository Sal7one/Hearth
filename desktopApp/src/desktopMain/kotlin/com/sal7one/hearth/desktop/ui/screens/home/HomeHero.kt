package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.art.HearthArt
import com.sal7one.hearth.desktop.ui.art.rememberArtwork
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.Eyebrow
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Home's single hero: the captions artwork, the product's one sentence, the
 * language pair and the action that starts a session. Everything else on the page
 * is quieter than this block on purpose.
 */
@Composable
internal fun HomeHero(
    state: HomeState,
    onStart: () -> Unit,
    onSwapLanguages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val artwork = rememberArtwork(HearthArt.CAPTIONS)
    Box(
        modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(28.dp)),
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.22f),
                alpha = 0.95f,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to colors.bg,
                            0.36f to colors.bg.copy(alpha = 0.96f),
                            0.52f to colors.bg.copy(alpha = 0.86f),
                            0.74f to Color.Transparent,
                            1f to colors.bg.copy(alpha = 0.45f),
                        ),
                    ),
                ),
        )

        Column(
            Modifier
                .padding(horizontal = 44.dp, vertical = 40.dp)
                .widthIn(max = 620.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow(if (state.recognitionReady) HomeCopy.EYEBROW else "Live captions · setup needed")
            Spacer(Modifier.height(14.dp))
            Text(
                text = HomeCopy.HEADLINE_LINE_ONE + "\n" + HomeCopy.HEADLINE_LINE_TWO,
                style = HearthType.display.copy(fontSize = 42.sp, lineHeight = 45.sp),
                color = colors.text,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                HomeCopy.LEDE,
                style = HearthType.lede,
                color = colors.textDim,
                modifier = Modifier.widthIn(max = 470.dp),
            )

            Spacer(Modifier.height(26.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroLanguagePair(
                    sourceLabel = state.sourceLabel,
                    targetLabel = state.targetLabel,
                    onSwap = onSwapLanguages,
                )
                HearthButton(
                    text = HomeCopy.START,
                    onClick = onStart,
                    tone = ButtonTone.Primary,
                    size = ButtonSize.Large,
                    icon = HearthIcons.Microphone,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.subtitleChips.forEach { label ->
                    Chip(
                        label,
                        tone = if (label == "Voice off") ChipTone.Outline else ChipTone.Neutral,
                    )
                }
            }
        }
    }
}
