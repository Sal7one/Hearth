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
    maxLines: Int = 3,
) {
    val colors = HearthTheme.colors
    val settings = state.settings
    val caption = state.caption
    val alpha = settings.backdropFraction.coerceIn(0f, 1f)

    val (background, border, radius) = when (settings.style) {
        OverlayStyle.GLASS -> Triple(Color.Black.copy(alpha = 0.35f + 0.45f * alpha), Color.White.copy(alpha = 0.10f), 14.dp)
        OverlayStyle.BAR -> Triple(Color.Black.copy(alpha = 0.55f + 0.4f * alpha), Color.Transparent, 6.dp)
        OverlayStyle.CLEAR -> Triple(Color.Transparent, Color.Transparent, 0.dp)
    }

    Column(
        modifier
            .background(background, RoundedCornerShape(radius))
            .border(1.dp, border, RoundedCornerShape(radius))
            .padding(horizontal = (18 * scale).dp, vertical = (13 * scale).dp),
        verticalArrangement = Arrangement.spacedBy((4 * scale).dp),
    ) {
        if (caption == null) {
            Text(
                if (state.translationEnabled) OverlayCopy.EMPTY else OverlayCopy.EMPTY,
                style = HearthType.small.copy(fontSize = (13 * scale).sp),
                color = Color.White.copy(alpha = 0.55f),
            )
            return@Column
        }

        caption.original?.let { original ->
            Text(
                original,
                style = HearthType.small.copy(fontSize = (12.5f * scale).sp),
                color = Color.White.copy(alpha = if (settings.style == OverlayStyle.CLEAR) 0.78f else 0.62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            caption.primary,
            style = HearthType.transcriptTranslation.copy(
                fontSize = (settings.textSize.baseSp * scale).sp,
                lineHeight = (settings.textSize.baseSp * 1.34f * scale).sp,
                fontWeight = FontWeight.SemiBold,
                textDirection = if (state.targetRtl) TextDirection.Rtl else TextDirection.Ltr,
            ),
            color = Color.White,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )

        if (caption.partial && settings.showPartials) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size((6 * scale).dp).background(colors.accent, CircleShape))
                Spacer(Modifier.width((6 * scale).dp))
                Text(
                    OverlayCopy.PARTIAL,
                    style = HearthType.rowSub.copy(fontSize = (11 * scale).sp),
                    color = colors.accent,
                )
            }
        }
    }
}

/** Text shadow for the clear style is approximated with a translucent backing bar. */
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
