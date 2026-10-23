package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The floating window's settings, with a live sample of what it will look like.
 * The sample always comes from the current session — an empty session says so.
 */
@Composable
internal fun FloatingCaptionsCard(
    state: CaptionsState,
    onToggleOverlay: (Boolean) -> Unit,
    onShowOriginal: (Boolean) -> Unit,
    onShowPendingOriginal: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Text(CaptionsCopy.FLOATING_TITLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(CaptionsCopy.FLOATING_DETAIL, style = HearthType.small, color = colors.muted)

        Spacer(Modifier.height(13.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(colors.surface3, colors.bg, colors.surface2),
                    ),
                )
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val line = state.lines.lastOrNull { !it.partial } ?: state.lines.lastOrNull()
            val primary = line?.translation?.takeIf { it.isNotBlank() } ?: line?.original
            if (primary != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Column {
                        if (state.showOriginal && line?.translation != null) {
                            Text(
                                line.original,
                                style = HearthType.small.copy(fontSize = 11.sp),
                                color = Color.White.copy(alpha = 0.62f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                        }
                        Text(
                            primary,
                            style = if (state.targetRtl && line?.translation != null) {
                                HearthType.label.copy(fontSize = 14.sp, textDirection = TextDirection.Rtl)
                            } else {
                                HearthType.label.copy(fontSize = 14.sp)
                            },
                            color = Color.White,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    "Waiting for speech or text…",
                    style = HearthType.small,
                    color = colors.faint,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        Spacer(Modifier.height(13.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(
                CaptionsCopy.SHOW_ORIGINAL,
                CaptionsCopy.SHOW_ORIGINAL_DETAIL,
                state.showOriginal,
                onShowOriginal,
            )
            SwitchRow(
                CaptionsCopy.SHOW_PENDING,
                CaptionsCopy.SHOW_PENDING_DETAIL,
                state.showPendingOriginal,
                onShowPendingOriginal,
                enabled = state.translationEnabled,
            )
            SwitchRow(
                "Float above other apps",
                if (state.overlayVisible) "On screen now" else "Opens a small always-on-top window",
                state.overlayVisible,
                onToggleOverlay,
            )
        }
    }
}


