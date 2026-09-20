package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

    SettingsTabs { location ->
        val local = location == SettingsLocation.LOCAL
        SettingsLink(uiText(UiR.string.ui_easy_setup_35fb5), uiText(UiR.string.ui_local_or_cloud_in_two_steps_183c9), onEasySetup)
        SettingsHeading(if (local) uiText(UiR.string.ui_on_your_device_9f8fd) else uiText(UiR.string.ui_connected_services_35c48),
            if (local) uiText(UiR.string.ui_download_or_import_models_for_offline_use_693d9)
            else uiText(UiR.string.ui_providers_and_saved_keys_audio_or_text_goes_to_your_chosen_servic_11f7d))
        SettingsFeatureGrid(location) { onFeature(location, it) }
        if (local) {
            SettingsLink(uiText(UiR.string.ui_downloads_imports_a2a3b), if (ByokPolicy.FEATURE_BYOK) uiText(UiR.string.ui_download_folder_progress_and_installed_files_d9ced) else uiText(UiR.string.ui_import_model_files_from_your_device_18597), onDownloads)
            SettingsLink(uiText(UiR.string.ui_local_benchmark_3acfe), uiText(UiR.string.ui_compare_installed_speech_and_translation_models_3e1af), onBenchmark)
        }
        HorizontalDivider()
        SettingsAppPreferences(onAppearance, onShortcuts, onAdvanced, onHelp)
    }
}
