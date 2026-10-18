package com.sal7one.transiber.preferences

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SetupMode { BASIC, ADVANCED }
object SetupModeStore {
    private const val STORE = "hearth-setup"
    fun current(context: Context): SetupMode = fromStored(UserPreferences.store(context, STORE).getString("mode", null))
    fun mode(context: Context): Flow<SetupMode> = UserPreferences.values(context, STORE).map { fromStored(it["mode"] as? String) }
    fun setMode(context: Context, mode: SetupMode) { UserPreferences.store(context, STORE).edit().putString("mode", mode.name).apply() }
    private fun fromStored(value: String?) = SetupMode.entries.firstOrNull { it.name == value } ?: SetupMode.BASIC
}
@Composable fun rememberSetupMode(): SetupMode {
    val context = LocalContext.current.applicationContext
    val source = remember(context) { SetupModeStore.mode(context) }
    val mode by source.collectAsState(initial = SetupModeStore.current(context))
    return mode
}
