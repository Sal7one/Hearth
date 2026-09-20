package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy

/** One scroll/state bucket per tab. No API keys are placed in saved state. */
@Composable
internal fun SettingsTabs(
    initialLocation: SettingsLocation = SettingsLocation.LOCAL,
    entryRevision: Int = 0,
    content: @Composable ColumnScope.(SettingsLocation) -> Unit,
) {
    val locations = settingsLocations(ByokPolicy.FEATURE_BYOK)
    var state by rememberSaveable(stateSaver = listSaver(
        save = { value: SettingsTabState -> listOf(value.selected.ordinal, value.entry) },
        restore = { SettingsTabState(SettingsLocation.entries.getOrElse(it[0]) { SettingsLocation.LOCAL }, it[1]) },
    )) { mutableStateOf(SettingsTabState(initialLocation, -1)) }
    val entered = state.enter(initialLocation, entryRevision, ByokPolicy.FEATURE_BYOK)
    if (entered != state) state = entered
    val selected = entered.selected
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        if (locations.size > 1) {
            TabRow(selectedTabIndex = locations.indexOf(selected)) {
                locations.forEach { location ->
                    Tab(selected = selected == location, onClick = { state = state.copy(selected = location) },
                        text = { Text(location.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        pages.SaveableStateProvider(selected.name) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) { content(selected) }
        }
    }
}

@Composable
internal fun SettingsHeading(title: String, detail: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
