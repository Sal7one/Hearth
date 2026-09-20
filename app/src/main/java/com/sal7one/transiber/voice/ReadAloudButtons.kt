package com.sal7one.transiber.voice

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One-tap playback uses the saved default; the adjacent menu keeps both explicit routes. */
@Composable
internal fun ReadAloudButtons(
    label: String,
    enabled: Boolean = true,
    onPlay: (system: Boolean) -> Unit,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    val custom = VoiceSettings.customBackend(context) != null
    val systemDefault = VoiceSettings.choice(context).backend == "system"
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPlay(systemDefault) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Listen to $label with ${if (systemDefault) "Android" else "selected custom"} voice" }) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Listen · $label")
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = enabled) {
                Icon(Icons.Default.KeyboardArrowDown, "Voice options for $label")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Android voice") }, onClick = { expanded = false; onPlay(true) })
                DropdownMenuItem(text = { Text(if (custom) "Custom voice" else "Set up custom voice") }, onClick = {
                    expanded = false; if (custom) onPlay(false) else onSetup()
                })
                DropdownMenuItem(text = { Text("Voice settings") }, onClick = { expanded = false; onSetup() })
            }
        }
    }
}
