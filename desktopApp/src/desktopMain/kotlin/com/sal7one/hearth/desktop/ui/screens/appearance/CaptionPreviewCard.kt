package com.sal7one.hearth.desktop.ui.screens.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.screens.overlay.CaptionLayer
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayPosition
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import androidx.compose.ui.unit.sp

/**
 * The floating window as the current session would draw it, using the real caption
 * layer. With no session it says so instead of showing invented subtitles.
 */
@Composable
internal fun CaptionPreviewCard(
    overlay: OverlayState,
    onOpenOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppearanceCopy.CAPTIONS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text(AppearanceCopy.CAPTIONS_DETAIL, style = HearthType.small, color = colors.muted)
            }
            HearthButton(
                AppearanceCopy.MOVE,
                onOpenOverlay,
                tone = ButtonTone.Ghost,
                size = ButtonSize.Small,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(colors.surface3, colors.bg, colors.surface2),
                    ),
                ),
            contentAlignment = when (overlay.settings.position) {
                OverlayPosition.TOP -> Alignment.TopCenter
                OverlayPosition.CENTER -> Alignment.Center
                OverlayPosition.BOTTOM -> Alignment.BottomCenter
            },
        ) {
            CaptionLayer(
                state = overlay,
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(overlay.settings.widthFraction.coerceIn(0.4f, 1f))
                    .padding(18.dp),
                scale = 0.9f,
            )
        }
    }
}

/** Kept small on purpose: this strip only points at where the data lives. */
@Composable
internal fun StorageStrip(
    storageLabel: String,
    freeLabel: String,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppearanceCopy.WHERE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text(
                    "$storageLabel models · $freeLabel free",
                    style = HearthType.small,
                    color = colors.muted,
                )
            }
            HearthButton(
                AppearanceCopy.STORAGE,
                onOpenFiles,
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}
