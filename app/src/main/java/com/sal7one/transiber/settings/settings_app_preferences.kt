package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.runtime.Composable

/** Common settings stay direct links rather than another collapsed section. */
@Composable
internal fun SettingsAppPreferences(onAppearance: () -> Unit, onShortcuts: () -> Unit, onAdvanced: () -> Unit, onHelp: () -> Unit) {
    val uiText = rememberUiText()

    com.sal7one.transiber.i18n.AppLanguageSettings()
    SettingsHeading(uiText(UiR.string.ui_app_fc4a6), uiText(UiR.string.ui_shared_across_local_and_cloud_82c69))
    SettingsLink(uiText(UiR.string.ui_appearance_navigation_433af), uiText(UiR.string.ui_themes_light_or_dark_simple_home_or_classic_tabs_24bee), onAppearance)
    SettingsLink(uiText(UiR.string.ui_phone_shortcuts_b969d), uiText(UiR.string.ui_start_either_overlay_from_quick_settings_f7593), onShortcuts)
    SettingsLink(uiText(UiR.string.ui_caption_overlay_controls_64e81), uiText(UiR.string.ui_audio_languages_appearance_and_accessibility_04684), onAdvanced)
    SettingsLink(uiText(UiR.string.ui_help_diagnostics_68022), uiText(UiR.string.ui_setup_guidance_and_actual_engine_errors_30852), onHelp)
}
