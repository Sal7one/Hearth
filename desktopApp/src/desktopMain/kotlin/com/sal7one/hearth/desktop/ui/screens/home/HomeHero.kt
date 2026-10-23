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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.sal7one.hearth.desktop.ui.language.LanguagePairPicker

/**
 * Home's single hero: the captions artwork, the product's one sentence, the
 * language pair and the action that starts a session. Everything else on the page
 * is quieter than this block on purpose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeHero(
    state: HomeState,
    onStart: () -> Unit,
    onSwapLanguages: () -> Unit,
    modifier: Modifier = Modifier,
    onSourceLanguage: (String) -> Unit = {},
    onTargetLanguage: (String) -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenInput: () -> Unit = {},
) {
    val colors = HearthTheme.colors
    val artwork = rememberArtwork(HearthArt.CAPTIONS)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp)
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
            Eyebrow(when {state.setupVerifying -> "Live captions · checking saved setup";state.recognitionReady -> HomeCopy.EYEBROW;else -> "Live captions · setup needed"})
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

            Spacer(Modifier.height(12.dp))
            HearthButton("Input: ${state.inputLabel}", onOpenInput, tone = ButtonTone.Ghost, size = ButtonSize.Small)
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LanguagePairPicker(state.languages, onSourceLanguage, onTargetLanguage, onSwapLanguages, enabled = !state.statusLive)
                HearthButton(
                    text = if (state.statusLive) "Open live captions" else if(state.setupVerifying) "Checking saved models…" else if (state.setupIssue != null) "Finish setup" else "Start live captions",
                    onClick = if (state.setupIssue != null && !state.statusLive) onOpenSetup else onStart,
                    tone = ButtonTone.Primary,
                    size = ButtonSize.Large,
                    icon = HearthIcons.Microphone,
                    enabled = state.statusLive || !state.setupVerifying && (state.setupIssue != null || state.languages.issue == null),
                )
            }

            (state.setupIssue ?: state.verificationMessage ?: state.languages.issue)?.let { issue ->
                Spacer(Modifier.height(8.dp))
                Text(issue, style = HearthType.small, color = if(state.setupVerifying)colors.muted else colors.warn)
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
