package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet

/** One shared setup sheet, reachable directly from every feature. */
@Composable
internal fun SettingsQuickSheet(
    onDismiss: () -> Unit,
    onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onPage: (Int) -> Unit,
    onReading: () -> Unit,
) {
    var location by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    FeatureOptionsSheet("Settings", onDismiss) {
        val locations = settingsLocations(ByokPolicy.FEATURE_BYOK)
        if (locations.size > 1) TabRow(selectedTabIndex = locations.indexOf(location)) {
            locations.forEach { option -> Tab(selected = option == location, onClick = { location = option }, text = { Text(option.label) }) }
        }
        SettingsFeatureGrid(location) { onFeature(location, it) }
        FeatureAction("Appearance", Icons.Default.Palette, { onPage(13) })
        FeatureAction("Downloads & imports", Icons.Default.Download, { onPage(2) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPage(5) }) { Text("Caption overlay") }
            TextButton(onClick = onReading) { Text("Screen overlay") }
        }
        OutlinedButton(onClick = { onPage(3) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("All settings & diagnostics") }
    }
}
