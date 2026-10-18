package com.sal7one.transiber.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.SetupModeStore
import com.sal7one.transiber.preferences.rememberSetupMode

/** The same persisted mode applies to every entry point, including TV setup. */
@Composable
internal fun SetupModePicker() {
    val text = rememberUiText()
    val context = LocalContext.current
    val mode = rememberSetupMode()
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(text(if (mode == SetupMode.BASIC) R.string.setup_mode_basic else R.string.setup_mode_advanced))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            SetupMode.entries.forEach { choice ->
                DropdownMenuItem(text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text(if (choice == SetupMode.BASIC) R.string.setup_mode_basic else R.string.setup_mode_advanced))
                        Text(text(if (choice == SetupMode.BASIC) R.string.setup_mode_basic_detail else R.string.setup_mode_advanced_detail),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = { SetupModeStore.setMode(context, choice); open = false })
            }
        }
    }
}

@Composable
internal fun AdvancedSetupLink() {
    val text = rememberUiText()
    val context = LocalContext.current
    TextButton(onClick = { SetupModeStore.setMode(context, SetupMode.ADVANCED) }, modifier = Modifier.fillMaxWidth()) {
        Text(text(R.string.setup_mode_open_advanced))
    }
}
