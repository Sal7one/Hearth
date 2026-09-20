package com.sal7one.transiber.settings

import androidx.compose.runtime.Composable

/** Common settings stay direct links rather than another collapsed section. */
@Composable
internal fun SettingsAppPreferences(onAppearance: () -> Unit, onShortcuts: () -> Unit, onAdvanced: () -> Unit, onHelp: () -> Unit) {
    SettingsHeading("App", "Shared across Local and Cloud.")
    SettingsLink("Appearance & navigation", "Themes, light or dark, simple home or classic tabs", onAppearance)
    SettingsLink("Phone shortcuts", "Start either overlay from Quick Settings", onShortcuts)
    SettingsLink("Caption overlay controls", "Audio, languages, appearance and accessibility", onAdvanced)
    SettingsLink("Help & diagnostics", "Setup guidance and actual engine errors", onHelp)
}
