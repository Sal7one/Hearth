package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.sal7one.transiber.preferences.*
import com.sal7one.transiber.setup.AdvancedSetupLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import androidx.compose.runtime.saveable.rememberSaveable
import com.sal7one.transiber.runtime.LocalModelPolicy

@Composable
internal fun SettingsScreen(
    initialConfig: com.sal7one.transiber.caption.CaptionOverlayConfig? = null,
    onEasySetup: () -> Unit,
    onAppearance: () -> Unit,
    onShortcuts: () -> Unit,
    onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onTranslationModels: () -> Unit,
    onDownloads: () -> Unit,
    onBenchmark: () -> Unit,
    onAdvanced: () -> Unit,
    onHelp: () -> Unit,
    onHistory: () -> Unit,
) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val advanced = rememberSetupMode() == SetupMode.ADVANCED
    val localAllowed = LocalModelPolicy.allowed(context)
    var tools by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsPipelineUi(onFeature, onTranslationModels, initialConfig)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (localAllowed) OutlinedButton(onClick = onEasySetup) { Text(uiText(UiR.string.settings_flow_bundles)) }
            else if (ByokPolicy.FEATURE_BYOK) OutlinedButton(onClick = onEasySetup) { Text(uiText(UiR.string.ui_easy_setup_35fb5)) }
            if (localAllowed) OutlinedButton(onClick = onDownloads) { Text(uiText(UiR.string.ui_downloads_imports_a2a3b)) }
            if (localAllowed) TextButton(onClick = { onFeature(SettingsLocation.LOCAL, SettingsFeature.CAMERA) }) {
                Text(uiText(UiR.string.ui_camera_ocr_c1207))
            }
        }
        HorizontalDivider()
        SettingsAppPreferences(onAppearance, onShortcuts, onAdvanced, onHelp)
        TextButton(onClick = { tools = !tools }) { Text(uiText(UiR.string.settings_flow_tools)) }
        if (tools) {
            if (advanced) com.sal7one.transiber.caption.history.CaptionHistorySettings(onHistory)
            else SettingsLink(uiText(UiR.string.caption_history_title), uiText(UiR.string.settings_flow_history_detail), onHistory)
            if (advanced && localAllowed) SettingsLink(uiText(UiR.string.ui_local_benchmark_3acfe), uiText(UiR.string.ui_compare_installed_speech_and_translation_models_3e1af), onBenchmark)
        }
        if (!advanced) AdvancedSetupLink()
    }
}
