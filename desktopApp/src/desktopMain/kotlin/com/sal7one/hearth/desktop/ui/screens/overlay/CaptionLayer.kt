package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The caption itself. Rendered identically inside the preview scene and inside the
 * real floating window, so the two can never disagree — the window just draws it at
 * 1× and the scene at a preview scale.
 */
@Composable
internal fun CaptionLayer(
    state: OverlayState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    maxLines: Int = state.settings.maxLines,
) {
    val colors = HearthTheme.colors
    val settings = state.settings
    val caption = state.caption
    val opacity = settings.backdropFraction.coerceIn(0f, 1f)
    val background = if (settings.style == OverlayStyle.CLEAR) Color.Transparent else Color.Black.copy(alpha = opacity)
    val radius = if (settings.style == OverlayStyle.BAR) 5.dp else 14.dp
    val base = settings.textSize.baseSp * settings.textScale.coerceIn(0.65f, 2f) * scale
    val alignment = if (settings.textAlign == OverlayTextAlign.CENTER) androidx.compose.ui.text.style.TextAlign.Center
        else androidx.compose.ui.text.style.TextAlign.Start
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.95f), androidx.compose.ui.geometry.Offset(0f, 2f), 7f)
    Column(
        modifier.background(background, RoundedCornerShape(radius))
            .border(if (settings.style == OverlayStyle.GLASS) 1.dp else 0.dp,
                if (settings.style == OverlayStyle.GLASS) Color.White.copy(alpha = opacity * 0.14f) else Color.Transparent,
                RoundedCornerShape(radius))
            .padding(horizontal = (16 * scale).dp, vertical = (10 * scale).dp),
        verticalArrangement = Arrangement.spacedBy((5 * scale).dp),
    ) {
        if (caption == null) {
            Text(OverlayCopy.EMPTY, modifier = Modifier.fillMaxWidth(),
                style = HearthType.small.copy(fontSize = (15 * scale).sp, shadow = shadow, textAlign = alignment),
                color = Color.White.copy(alpha = 0.7f))
            return@Column
        }
        caption.original?.let { original ->
            Text(original, modifier = Modifier.fillMaxWidth(),
                style = HearthType.transcriptOriginal.copy(fontSize = (base * 0.65f).sp,
                    lineHeight = (base * 0.9f).sp, letterSpacing = 0.sp, shadow = shadow,
                    textDirection = TextDirection.ContentOrLtr, textAlign = alignment),
                color = Color.White.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(caption.primary, modifier = Modifier.fillMaxWidth(),
            style = HearthType.transcriptTranslation.copy(fontSize = base.sp, lineHeight = (base * 1.38f).sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.sp, shadow = shadow, textAlign = alignment,
                textDirection = if (state.targetRtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr),
            color = Color.White.copy(alpha = if (caption.partial) 0.88f else 1f),
            maxLines = maxLines.coerceIn(1, 6), overflow = TextOverflow.Ellipsis)
        if (caption.partial && settings.showPartials) {
            Box(Modifier.align(Alignment.CenterHorizontally).size((4 * scale).dp).background(colors.accent, CircleShape))
        }
    }
}

/** Optional preview scrim; live subtitles use the text shadow in CaptionLayer. */
@Composable
internal fun CaptionScrim(state: OverlayState, modifier: Modifier = Modifier) {
    if (state.settings.style != OverlayStyle.CLEAR) {
        Box(modifier)
        return
    }
    Box(
        modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                ),
            ),
    )
}

@Composable
internal fun CaptionWidthWrapper(
    fraction: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(fraction.coerceIn(0.2f, 1f))) { content() }
}

/** Warm caption colour used by the live marker in the scene header. */
internal val LiveDot = Color(0xFF3BE1EC)

@Composable
internal fun LiveBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(LiveDot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = HearthType.small.copy(fontSize = 11.sp), color = Color.White.copy(alpha = 0.82f))
    }
}

@Composable
internal fun SceneHeader(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(HearthTheme.colors.surface2)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (index == 0) HearthTheme.colors.raised else HearthTheme.colors.raised, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            OverlayCopy.WINDOW_LABEL,
            style = HearthType.small.copy(fontSize = 11.sp),
            color = HearthTheme.colors.faint,
        )
    }
}
