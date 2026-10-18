package com.sal7one.transiber.translation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.flow.map

/** Render persisted choices directly: screen restoration must never write an old pair back. */
internal data class TypedTranslationPreferences(
    val source: String = "en",
    val target: String = "ar",
    val automatic: Boolean = true,
) {
    companion object {
        fun from(values: Map<String, *>): TypedTranslationPreferences = TypedTranslationPreferences(
            source = values["source"] as? String ?: "en",
            target = values["target"] as? String ?: "ar",
            automatic = values["automatic"] as? Boolean ?: true,
        )
    }
}

internal object TypedTranslationSettings {
    private const val STORE = "typed-translation"
    private fun preferences(context: Context) = UserPreferences.store(context, STORE)
    fun current(context: Context) = TypedTranslationPreferences.from(preferences(context).all)
    fun values(context: Context) = UserPreferences.values(context, STORE).map { TypedTranslationPreferences.from(it) }

    // Every language gesture writes one pair in a single snapshot. Other saved
    // choices, including automatic mode, remain independent of that gesture.
    private fun pair(context: Context, source: String, target: String) {
        preferences(context).edit().putString("source", source).putString("target", target).apply()
    }
    fun source(context: Context, source: String) { pair(context, source, current(context).target) }
    fun target(context: Context, target: String) { pair(context, current(context).source, target) }
    fun swap(context: Context) { current(context).let { pair(context, it.target, it.source) } }
    fun automatic(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("automatic", enabled).apply()
    }
}

@Composable
internal fun rememberTypedTranslationPreferences(): TypedTranslationPreferences {
    val context = LocalContext.current.applicationContext
    val changes = remember(context) { TypedTranslationSettings.values(context) }
    val preferences by changes.collectAsState(initial = TypedTranslationSettings.current(context))
    return preferences
}

/** A share action can choose how that input is processed without changing the user's preference. */
internal enum class TypedInputOrigin { TYPED, SHARED_MANUAL, SHARED_IMMEDIATE;
    fun automatic(saved: Boolean): Boolean = when (this) {
        TYPED -> saved
        SHARED_MANUAL -> false
        SHARED_IMMEDIATE -> true
    }
    fun edited(text: String): TypedInputOrigin = if (text.isBlank()) TYPED else this
    companion object {
        fun shared(immediate: Boolean) = if (immediate) SHARED_IMMEDIATE else SHARED_MANUAL
    }
}
