package com.sal7one.transiber.ui.theme

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

/** Shared listener keeps separate Compose windows in sync with the app setting. */
@Composable
fun rememberThemeMode(): ThemeMode {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE) }
    var mode by remember(preferences) { mutableStateOf(ThemeMode.fromStored(preferences.getString(THEME_KEY, null))) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == THEME_KEY || key == null) mode = ThemeMode.fromStored(prefs.getString(THEME_KEY, null))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        mode = ThemeMode.fromStored(preferences.getString(THEME_KEY, null))
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode
}

/** Compact setup control. System is the default, including unknown older values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val mode = rememberThemeMode()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = {
                        context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)
                            .edit().putString(THEME_KEY, option.name).apply()
                    },
                    label = { Text(option.label) },
                )
            }
        }
        Text("System follows your phone. Bubble appearance has its own controls.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
