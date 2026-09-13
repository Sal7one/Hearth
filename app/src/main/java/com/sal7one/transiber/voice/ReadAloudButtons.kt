package com.sal7one.transiber.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Explicit system playback never changes the user's shared/default custom selection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadAloudButtons(
    label: String,
    enabled: Boolean = true,
    onPlay: (system: Boolean) -> Unit,
    onSetup: () -> Unit,
) {
    val custom = VoiceSettings.customBackend(LocalContext.current) != null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { onPlay(true) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Play $label with Android voice" },
        ) { Text("Android · $label") }
        TextButton(
            onClick = { if (custom) onPlay(false) else onSetup() }, enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = if (custom) "Play $label with selected custom voice" else "Choose default custom voice for $label"
            },
        ) { Text(if (custom) "Custom · $label" else "Choose custom voice") }
    }
}
