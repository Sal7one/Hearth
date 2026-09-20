package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy

@Composable
internal fun SettingsScreen(
    onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onDownloads: () -> Unit,
    onBenchmark: () -> Unit,
    onAdvanced: () -> Unit,
    onHelp: () -> Unit,
) {
    SettingsTabs { location ->
        val local = location == SettingsLocation.LOCAL
        SettingsHeading(if (local) "On your device" else "Connected services",
            if (local) "Models run on your phone. Download or import once, then use them offline."
            else "Manage providers and saved keys. Cloud features send audio or text to the service you choose.")
        settingsFeatures(location).forEach { feature ->
            val detail = when (feature) {
                SettingsFeature.SPEECH -> if (local) "Nemotron, Qwen, Moonshine, Whisper and Vosk" else "Streaming providers, speech models and API keys"
                SettingsFeature.TRANSLATION -> if (local) "Choose a translator, import models and manage language packs" else "Google, Microsoft, DeepL and LibreTranslate"
                SettingsFeature.VOICES -> if (local) "Android voices and downloadable Supertonic voices" else "Connect your own voice server"
                SettingsFeature.CAMERA -> "OCR models for camera, images, manga and books"
            }
            SettingsLink(feature.label, detail) { onFeature(location, feature) }
        }
        if (local) {
            SettingsLink("Downloads & imports", if (ByokPolicy.FEATURE_BYOK) "Download folder, progress and installed files" else "Import model files from your device", onDownloads)
            SettingsLink("Local benchmark", "Compare installed speech and translation models", onBenchmark)
        }
        HorizontalDivider()
        SettingsShortcutsUi()
        HorizontalDivider()
        SettingsAppPreferences(onAdvanced, onHelp)
    }
}
