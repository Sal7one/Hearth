package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

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
    val uiText = rememberUiText()

    var location by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    FeatureOptionsSheet(uiText(UiR.string.ui_settings_c7f73), onDismiss) {
        Button(onClick={onPage(15)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(uiText(UiR.string.ui_easy_setup_local_or_cloud_6c3e9))}
        val locations = settingsLocations(ByokPolicy.FEATURE_BYOK)
        if (locations.size > 1) TabRow(selectedTabIndex = locations.indexOf(location)) {
            locations.forEach { option -> Tab(selected = option == location, onClick = { location = option }, text = { Text(uiText.label(option)) }) }
        }
        SettingsFeatureGrid(location) { onFeature(location, it) }
        FeatureAction(uiText(UiR.string.ui_appearance_41def), Icons.Default.Palette, { onPage(13) })
        FeatureAction(uiText(UiR.string.ui_downloads_imports_a2a3b), Icons.Default.Download, { onPage(2) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPage(5) }) { Text(uiText(UiR.string.ui_caption_overlay_a4c78)) }
            TextButton(onClick = onReading) { Text(uiText(UiR.string.ui_screen_overlay_773a6)) }
        }
        OutlinedButton(onClick = { onPage(3) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText(UiR.string.ui_all_settings_diagnostics_7b22b)) }
    }
}
