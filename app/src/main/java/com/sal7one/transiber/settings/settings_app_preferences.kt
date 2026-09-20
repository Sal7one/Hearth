package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.ui.theme.AppearanceSettings

@Composable
internal fun SettingsAppPreferences(onAdvanced: () -> Unit, onHelp: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (expanded) "App preferences · hide" else "App preferences")
    }
    Text("Appearance, caption controls and help apply to both Local and Cloud.", style = MaterialTheme.typography.bodySmall)
    if (expanded) {
        AppearanceSettings()
        SettingsLink("Advanced caption controls", "Audio, languages, overlay appearance and accessibility controls", onAdvanced)
        SettingsLink("Help & diagnostics", "Setup guidance and actual engine errors", onHelp)
    }
}
