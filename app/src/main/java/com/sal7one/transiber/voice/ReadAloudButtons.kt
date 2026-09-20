package com.sal7one.transiber.voice

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

    val context = LocalContext.current
    val custom = VoiceSettings.customBackend(context) != null
    val systemDefault = VoiceSettings.choice(context).backend == "system"
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPlay(systemDefault) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_listen_to_1_s_with_2_s_voice_2cadb, label, if (systemDefault) "Android" else uiText(UiR.string.selected_custom)) }) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(uiText(UiR.string.ui_listen_1_s_3cf7d, label))
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = enabled) {
                Icon(Icons.Default.KeyboardArrowDown, uiText(UiR.string.ui_voice_options_for_1_s_978e2, label))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(uiText(UiR.string.ui_android_voice_ba5fb)) }, onClick = { expanded = false; onPlay(true) })
                DropdownMenuItem(text = { Text(if (custom) uiText(UiR.string.ui_custom_voice_e8546) else uiText(UiR.string.ui_set_up_custom_voice_8b3a7)) }, onClick = {
                    expanded = false; if (custom) onPlay(false) else onSetup()
                })
                DropdownMenuItem(text = { Text(uiText(UiR.string.ui_voice_settings_9f03d)) }, onClick = { expanded = false; onSetup() })
            }
        }
    }
}
