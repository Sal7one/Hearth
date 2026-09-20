package com.sal7one.transiber.ui.theme

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val APPEARANCE_PREFS = "hearth-appearance"
private const val THEME_KEY = "theme-mode"
private const val ACCENT_KEY = "accent-preset"
private const val LAYOUT_KEY = "navigation-layout"

enum class NavigationLayout(val label: String) {
    SIMPLE("Simple home"), TABS("Classic tabs");
    companion object {
        fun fromStored(value: String?): NavigationLayout = entries.firstOrNull { it.name == value } ?: SIMPLE
    }
}

@Composable
private fun rememberAppearanceValue(key: String): String? {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE) }
    var value by remember(prefs, key) { mutableStateOf(prefs.getString(key, null)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, changed ->
            if (changed == key || changed == null) value = p.getString(key, null)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = prefs.getString(key, null)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
fun rememberAccentPreset(): AccentPreset = AccentPreset.fromStored(rememberAppearanceValue(ACCENT_KEY))

@Composable
fun rememberColorfulUi(): Boolean = rememberAppearanceValue("surface-style") != "minimal"

@Composable
fun rememberNavigationLayout(): NavigationLayout = NavigationLayout.fromStored(rememberAppearanceValue(LAYOUT_KEY))


/** All Compose windows observe the same persisted appearance values. */
@Composable
fun rememberThemeMode(): ThemeMode = ThemeMode.fromStored(rememberAppearanceValue(THEME_KEY))

/** Compact setup control. Ink and Dark are the defaults; explicit saved choices remain unchanged. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings(modifier: Modifier = Modifier) {
    val uiText = rememberUiText()

    val context = LocalContext.current.applicationContext
    val mode = rememberThemeMode()
    val accent = rememberAccentPreset()
    val layout = rememberNavigationLayout()
    val colorful = rememberColorfulUi()
    val prefs = remember(context) { context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.sal7one.transiber.i18n.AppLanguageSettings()
        Text(uiText(UiR.string.ui_cards_background_7cc95), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=colorful,onClick={prefs.edit().putString("surface-style","colorful").apply()},label={Text(uiText(UiR.string.ui_color_blur_74755))})
            FilterChip(selected=!colorful,onClick={prefs.edit().putString("surface-style","minimal").apply()},label={Text(uiText(UiR.string.ui_minimal_backup_d48a8))})
        }
        Text(uiText(UiR.string.ui_colorful_cards_with_softly_blurred_artwork_minimal_removes_decora_ac1d6),style=MaterialTheme.typography.bodySmall)
        Text(uiText(UiR.string.ui_light_dark_1a3d4), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = {
                        context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)
                            .edit().putString(THEME_KEY, option.name).apply()
                    },
                    label = { Text(uiText.label(option)) },
                )
            }
        }
        Text(uiText(UiR.string.ui_theme_a797e), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentPreset.entries.forEach { option ->
                FilterChip(selected = accent == option,
                    onClick = { prefs.edit().putString(ACCENT_KEY, option.name).apply() },
                    label = { Text(uiText.label(option)) })
            }
        }
        Text(uiText.description(accent), style = MaterialTheme.typography.bodySmall)
        Text(uiText(UiR.string.ui_navigation_cf03c), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavigationLayout.entries.forEach { option ->
                FilterChip(selected = layout == option,
                    onClick = { prefs.edit().putString(LAYOUT_KEY, option.name).apply() },
                    label = { Text(uiText.label(option)) })
            }
        }
        Text(uiText(UiR.string.ui_simple_home_puts_every_feature_in_one_place_classic_tabs_keeps_th_bde4c), style = MaterialTheme.typography.bodySmall)
        Text(uiText(UiR.string.ui_system_follows_your_phone_bubble_appearance_has_its_own_controls_9159c),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
