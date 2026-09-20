package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy

@Composable
internal fun SettingsScreen(
    onEasySetup: () -> Unit,
    onAppearance: () -> Unit,
    onShortcuts: () -> Unit,
    onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onDownloads: () -> Unit,
    onBenchmark: () -> Unit,
    onAdvanced: () -> Unit,
    onHelp: () -> Unit,
) {
    SettingsTabs { location ->
        val local = location == SettingsLocation.LOCAL
        SettingsLink("Easy setup", "Local or Cloud in two steps", onEasySetup)
        SettingsHeading(if (local) "On your device" else "Connected services",
            if (local) "Download or import models for offline use."
            else "Providers and saved keys. Audio or text goes to your chosen service.")
        SettingsFeatureGrid(location) { onFeature(location, it) }
        if (local) {
            SettingsLink("Downloads & imports", if (ByokPolicy.FEATURE_BYOK) "Download folder, progress and installed files" else "Import model files from your device", onDownloads)
            SettingsLink("Local benchmark", "Compare installed speech and translation models", onBenchmark)
        }
        HorizontalDivider()
        SettingsAppPreferences(onAppearance, onShortcuts, onAdvanced, onHelp)
    }
}
