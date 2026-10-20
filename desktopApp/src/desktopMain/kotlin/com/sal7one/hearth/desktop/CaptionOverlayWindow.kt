package com.sal7one.hearth.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.rememberWindowState
import com.sal7one.hearth.core.PipelineSnapshot

@Composable
internal fun CaptionOverlayWindow(snapshot: PipelineSnapshot?, showOriginal: Boolean, translationEnabled: Boolean,
    showOriginalWhilePending: Boolean, onClose: () -> Unit) {
    Window(onCloseRequest = onClose, title = "Hearth captions", alwaysOnTop = true, undecorated = true,
        transparent = true, state = rememberWindowState(width = 780.dp, height = 230.dp)) {
        HearthDesktopTheme {
            WindowDraggableArea {
                Column(Modifier.fillMaxSize().background(Color(0xEE171615), RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Hearth captions · drag to move", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCCBDB0))
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                    val caption = overlayCaption(snapshot?.segments.orEmpty(), showOriginal, translationEnabled, showOriginalWhilePending)
                    Text(caption?.text ?: if (translationEnabled && !showOriginalWhilePending) "Waiting for translation…" else "Waiting for speech or text…",
                        color = Color.White, fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    caption?.original?.let { Text(it, color = Color(0xFFD0C6BC), fontSize = 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (caption?.partial == true) Text("Listening · partial", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF2B394))
                    snapshot?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
