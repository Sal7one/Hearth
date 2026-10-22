package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.CaptionSpeakerChoice
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.rememberSetupMode
import com.sal7one.transiber.runtime.LocalModelPolicy
import com.sal7one.transiber.setup.AdvancedSetupLink
import com.sal7one.transiber.ui.components.FeatureAction

/** A visible model lab and a searchable directory; no tools are hidden behind a second menu. */
@Composable
internal fun SettingsScreen(
    initialConfig: CaptionOverlayConfig? = null,
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
    val text = rememberUiText()
    val context = LocalContext.current
    val advanced = rememberSetupMode() == SetupMode.ADVANCED
    val localAllowed = LocalModelPolicy.allowed(context)
    var query by rememberSaveable { mutableStateOf(SettingsDirectoryPreferences.query(context)) }
    fun setQuery(value: String) {
        query = value.take(SettingsDirectoryPreferences.MAX_QUERY_LENGTH)
        SettingsDirectoryPreferences.setQuery(context, query)
    }
    // Searching must not destroy the position in the full settings directory.
    val directoryScroll = rememberScrollState()
    val searchScroll = rememberScrollState()
    val searching = query.isNotBlank()
    val entries = settingsDirectoryEntries(ByokPolicy.FEATURE_BYOK, localAllowed, advanced)
    fun openFeature(feature: SettingsFeature) {
        val preferred = when (feature) {
            SettingsFeature.SPEECH -> if (initialConfig?.effectiveEngine == CaptionEngineChoice.CLOUD) SettingsLocation.CLOUD else SettingsLocation.LOCAL
            SettingsFeature.TRANSLATION -> if (initialConfig?.textTranslationProviderId.orEmpty() in setOf("", "local")) SettingsLocation.LOCAL else SettingsLocation.CLOUD
            SettingsFeature.VOICES -> if (when (initialConfig?.speakerChoice) {
                CaptionSpeakerChoice.CLOUD -> true
                CaptionSpeakerChoice.CUSTOM -> com.sal7one.transiber.voice.VoiceSettings.customBackend(context) == "remote"
                CaptionSpeakerChoice.SHARED -> com.sal7one.transiber.voice.VoiceSettings.choice(context).backend == "remote"
                else -> false
            }) SettingsLocation.CLOUD else SettingsLocation.LOCAL
            SettingsFeature.CAMERA -> SettingsLocation.LOCAL
        }
        settingsStageDestination(preferred, feature, ByokPolicy.FEATURE_BYOK, localAllowed)?.let { onFeature(it, feature) }
    }
    fun open(entry: SettingsDirectoryEntry) = when (entry) {
        SettingsDirectoryEntry.BENCHMARK -> onBenchmark()
        SettingsDirectoryEntry.DOWNLOADS -> onDownloads()
        SettingsDirectoryEntry.BUNDLES -> onEasySetup()
        SettingsDirectoryEntry.SPEECH -> openFeature(SettingsFeature.SPEECH)
        SettingsDirectoryEntry.TRANSLATION -> openFeature(SettingsFeature.TRANSLATION)
        SettingsDirectoryEntry.VOICES -> openFeature(SettingsFeature.VOICES)
        SettingsDirectoryEntry.CAMERA -> openFeature(SettingsFeature.CAMERA)
        SettingsDirectoryEntry.APPEARANCE -> onAppearance()
        SettingsDirectoryEntry.SHORTCUTS -> onShortcuts()
        SettingsDirectoryEntry.OVERLAY -> onAdvanced()
        SettingsDirectoryEntry.HISTORY -> onHistory()
        SettingsDirectoryEntry.HELP -> onHelp()
        SettingsDirectoryEntry.LANGUAGE -> Unit // An inline language picker, never a dead-end page.
    }
    SettingsPageContent(scroll = if (searching) searchScroll else directoryScroll) {
        OutlinedTextField(
            value = query, onValueChange = ::setQuery,
            label = { Text(text(UiR.string.settings_directory_search)) },
            placeholder = { Text(text(UiR.string.settings_directory_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { setQuery("") }) {
                Icon(Icons.Default.Close, text(UiR.string.settings_directory_clear_search))
            } },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("settings-search"),
        )
        if (searching) {
            var matches = 0
            entries.forEach { entry ->
                val (title, detail) = settingsDirectoryLabels(entry)
                if (settingsSearchMatches(entry, query, title, detail)) {
                    matches++
                    if (entry == SettingsDirectoryEntry.LANGUAGE) com.sal7one.transiber.i18n.AppLanguageSettings()
                    else SettingsLink(title, detail) { open(entry) }
                }
            }
            if (matches == 0) Text(text(UiR.string.settings_directory_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (localAllowed) {
                SettingsSection {
                    SettingsHeading(text(UiR.string.settings_directory_models), text(UiR.string.settings_directory_models_detail))
                    FeatureAction(text(UiR.string.settings_directory_benchmark), Icons.Default.Speed, onBenchmark,
                        detail = text(UiR.string.settings_directory_benchmark_detail),
                        modifier = Modifier.testTag("settings-benchmark"))
                    FeatureAction(text(UiR.string.ui_downloads_imports_a2a3b), Icons.Default.Download, onDownloads,
                        detail = text(UiR.string.settings_directory_downloads_detail),
                        modifier = Modifier.testTag("settings-downloads"))
                    SettingsLink(text(UiR.string.settings_flow_bundles), text(UiR.string.settings_directory_bundles_detail), onEasySetup)
                }
            } else if (ByokPolicy.FEATURE_BYOK) SettingsLink(text(UiR.string.ui_easy_setup_35fb5),
                text(UiR.string.settings_directory_bundles_detail), onEasySetup)
            SettingsSection {
                SettingsPipelineUi(onFeature, onTranslationModels, initialConfig)
                if (localAllowed) SettingsLink(text(UiR.string.label_settingsfeature_camera_label),
                    text(UiR.string.settings_directory_camera_detail)) { onFeature(SettingsLocation.LOCAL, SettingsFeature.CAMERA) }
            }
            SettingsSection {
                SettingsAppPreferences(onAppearance, onShortcuts, onAdvanced, onHelp)
            }
            SettingsSection {
                if (advanced) com.sal7one.transiber.caption.history.CaptionHistorySettings(onHistory)
                else SettingsLink(text(UiR.string.caption_history_title), text(UiR.string.settings_flow_history_detail), onHistory)
                if (!advanced) AdvancedSetupLink()
            }
        }
    }
}

@Composable
private fun settingsDirectoryLabels(entry: SettingsDirectoryEntry): Pair<String, String> {
    val text = rememberUiText()
    val (title, detail) = when (entry) {
        SettingsDirectoryEntry.BENCHMARK -> UiR.string.settings_directory_benchmark to UiR.string.settings_directory_benchmark_detail
        SettingsDirectoryEntry.DOWNLOADS -> UiR.string.ui_downloads_imports_a2a3b to UiR.string.settings_directory_downloads_detail
        SettingsDirectoryEntry.BUNDLES -> UiR.string.settings_flow_bundles to UiR.string.settings_directory_bundles_detail
        SettingsDirectoryEntry.SPEECH -> UiR.string.label_settingsfeature_speech_label to UiR.string.settings_directory_speech_detail
        SettingsDirectoryEntry.TRANSLATION -> UiR.string.label_settingsfeature_translation_label to UiR.string.settings_directory_translation_detail
        SettingsDirectoryEntry.VOICES -> UiR.string.label_settingsfeature_voices_label to UiR.string.settings_directory_voices_detail
        SettingsDirectoryEntry.CAMERA -> UiR.string.label_settingsfeature_camera_label to UiR.string.settings_directory_camera_detail
        SettingsDirectoryEntry.LANGUAGE -> UiR.string.settings_directory_language to UiR.string.settings_directory_language_detail
        SettingsDirectoryEntry.APPEARANCE -> UiR.string.ui_appearance_navigation_433af to UiR.string.ui_themes_light_or_dark_simple_home_or_classic_tabs_24bee
        SettingsDirectoryEntry.SHORTCUTS -> UiR.string.overlay_shortcuts_title to UiR.string.overlay_shortcuts_detail
        SettingsDirectoryEntry.OVERLAY -> UiR.string.ui_caption_overlay_controls_64e81 to UiR.string.ui_audio_languages_appearance_and_accessibility_04684
        SettingsDirectoryEntry.HISTORY -> UiR.string.caption_history_title to UiR.string.settings_flow_history_detail
        SettingsDirectoryEntry.HELP -> UiR.string.ui_help_diagnostics_68022 to UiR.string.ui_setup_guidance_and_actual_engine_errors_30852
    }
    return text(title) to text(detail)
}
