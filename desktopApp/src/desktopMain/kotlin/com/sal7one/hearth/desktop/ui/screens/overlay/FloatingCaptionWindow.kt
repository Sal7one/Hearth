package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/**
 * The real floating window. Undecorated and transparent, sized from the width
 * setting, with the caption anchored by the position setting. It draws the same
 * [CaptionLayer] the settings page previews.
 *
 * The window is only composed while it is meant to be visible; closing it is the
 * same action as switching it off.
 */
@Composable
internal fun FloatingCaptionWindow(
    state: OverlayState,
    dark: Boolean,
    preset: AccentPreset,
    onClose: () -> Unit,
) {
    val width = (900f * state.settings.widthFraction.coerceIn(0.4f, 1f)).dp
    Window(
        onCloseRequest = onClose,
        title = "Hearth captions",
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
        state = rememberWindowState(width = width, height = 220.dp),
    ) {
        HearthTheme(preset = preset, dark = dark) {
            Box(
                Modifier.fillMaxSize().padding(14.dp),
                contentAlignment = when (state.settings.position) {
                    OverlayPosition.TOP -> Alignment.TopCenter
                    OverlayPosition.CENTER -> Alignment.Center
                    OverlayPosition.BOTTOM -> Alignment.BottomCenter
                },
            ) {
                CaptionLayer(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                    scale = 1f,
                    maxLines = 4,
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        HearthIcons.Close,
                        contentDescription = "Close captions",
                        modifier = Modifier.size(11.dp),
                        tint = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
