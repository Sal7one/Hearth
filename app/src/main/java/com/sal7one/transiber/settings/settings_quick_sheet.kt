package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy

/** The same destinations as the directory, reachable without leaving a feature first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsQuickSheet(
    onDismiss: () -> Unit,
    onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onPage: (Int) -> Unit,
    onReading: () -> Unit,
) {
    var location by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { onPage(13) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Appearance & navigation") }
            val locations = settingsLocations(ByokPolicy.FEATURE_BYOK)
            if (locations.size > 1) TabRow(selectedTabIndex = locations.indexOf(location)) {
                locations.forEach { option -> Tab(selected = option == location, onClick = { location = option }, text = { Text(option.label) }) }
            }
            settingsFeatures(location).forEach { feature ->
                OutlinedButton(onClick = { onFeature(location, feature) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(feature.label) }
            }
            HorizontalDivider()
            TextButton(onClick = { onPage(5) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Caption overlay controls") }
            TextButton(onClick = onReading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Screen overlay controls") }
            TextButton(onClick = { onPage(2) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Downloads & imports") }
            TextButton(onClick = { onPage(3) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("All settings & diagnostics") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
