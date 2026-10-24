package com.sal7one.transiber.reading

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Uses Hearth's existing DataStore; no second preference file or competing state owner. */
internal class ReadingOverlayPreferences(context: Context) {
    private val app = context.applicationContext
    private val preferences get() = UserPreferences.store(app, "reading-overlay")
    fun read() = ReadingOverlaySettings.from(preferences.all)
    fun values() = UserPreferences.values(app, "reading-overlay")
        .map { ReadingOverlaySettings.from(it) }.distinctUntilChanged()
    fun update(change: (ReadingOverlaySettings) -> ReadingOverlaySettings) { write(change(read())) }
    fun write(value: ReadingOverlaySettings) {
        val next = value.bounded()
        preferences.edit().putString("mode", next.mode)
            .putLong("settle", next.settleMs).putLong("scan", next.scanMs)
            .putLong("refresh-cooldown", next.refreshCooldownMs).putString("change-sensitivity", next.changeSensitivity)
            .putBoolean("pass-through", next.passThrough).putBoolean("show-original", next.showOriginal)
            .putBoolean("translation-visible", next.translationVisible).putFloat("textSize", next.textSizeSp)
            .putBoolean("compact-handle", next.compactHandle)
            .putString("motion-exclusions", ReadingMotionExclusions.encode(next.motionExclusions)).apply()
    }
}

@Composable
internal fun rememberReadingOverlaySettings(): Pair<ReadingOverlaySettings, ReadingOverlayPreferences> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { ReadingOverlayPreferences(context) }
    val changes = remember(preferences) { preferences.values() }
    val settings by changes.collectAsState(initial = preferences.read())
    return settings to preferences
}
