package com.sal7one.transiber.settings

import android.content.Context
import com.sal7one.transiber.preferences.UserPreferences

/** Directory browsing survives app restarts without touching any pipeline preference. */
internal object SettingsDirectoryPreferences {
    const val MAX_QUERY_LENGTH = 256
    private fun store(context: Context) = UserPreferences.store(context, "settings-directory")
    fun query(context: Context): String = store(context).getString("query", "").orEmpty().take(MAX_QUERY_LENGTH)
    fun setQuery(context: Context, value: String) {
        store(context).edit().putString("query", value.take(MAX_QUERY_LENGTH)).apply()
    }
}
