package com.sal7one.transiber.settings

import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy

/** One scroll/state bucket per tab. No API keys are placed in saved state. */
@Composable
internal fun SettingsTabs(
    initialLocation: SettingsLocation = SettingsLocation.LOCAL,
    entryRevision: Int = 0,
    localAvailable: Boolean = true,
    content: @Composable ColumnScope.(SettingsLocation) -> Unit,
) {
    val uiText = rememberUiText()

    val locations = settingsLocations(ByokPolicy.FEATURE_BYOK, localAvailable)
    var state by rememberSaveable(stateSaver = listSaver(
        save = { value: SettingsTabState -> listOf(value.selected.ordinal, value.entry) },
        restore = { SettingsTabState(SettingsLocation.entries.getOrElse(it[0]) { SettingsLocation.LOCAL }, it[1]) },
    )) { mutableStateOf(SettingsTabState(initialLocation, -1)) }
    val entered = state.enter(initialLocation, entryRevision, ByokPolicy.FEATURE_BYOK, localAvailable)
    if (entered != state) state = entered
    val selected = entered.selected
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        if (locations.size > 1) {
            TabRow(selectedTabIndex = locations.indexOf(selected)) {
                locations.forEach { location ->
                    Tab(selected = selected == location, onClick = { state = state.copy(selected = location) },
                        text = { Text(uiText.label(location)) }, modifier = Modifier.heightIn(min = 48.dp))
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
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
