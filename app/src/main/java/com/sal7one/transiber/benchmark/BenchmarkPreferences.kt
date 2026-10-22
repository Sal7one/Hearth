package com.sal7one.transiber.benchmark

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.preferences.UserPreferences

/** Write at the interaction, so leaving the page cannot lose a pending preferences effect. */
private class BenchmarkPreferenceState<T>(private val state: MutableState<T>, private val save: (T) -> Unit) : MutableState<T> {
    override var value: T
        get() = state.value
        set(value) { if (value != state.value) { state.value = value; save(value) } }
    override fun component1() = value
    override fun component2(): (T) -> Unit = { value = it }
}

@Composable
private fun <T> rememberBenchmarkPreference(key: String, read: (SharedPreferences) -> T,
    write: SharedPreferences.Editor.(T) -> Unit): MutableState<T> {
    val context = LocalContext.current
    val store = remember(context) { UserPreferences.store(context, "benchmark-options") }
    val value = rememberSaveable(key) { mutableStateOf(read(store)) }
    return remember(key, store, value) { BenchmarkPreferenceState(value) { next -> store.edit().apply { write(next) }.apply() } }
}

@Composable internal fun rememberBenchmarkString(key: String, initial: String) = rememberBenchmarkPreference(key,
    { it.getString(key, initial) ?: initial }, { putString(key, it) })
@Composable internal fun rememberBenchmarkBoolean(key: String, initial: Boolean) = rememberBenchmarkPreference(key,
    { it.getBoolean(key, initial) }, { putBoolean(key, it) })
@Composable internal fun rememberBenchmarkInt(key: String, initial: Int) = rememberBenchmarkPreference(key,
    { it.getInt(key, initial) }, { putInt(key, it) })
@Composable internal fun rememberBenchmarkSelection(key: String) = rememberBenchmarkPreference(key,
    { it.getStringSet(key, emptySet())?.sorted().orEmpty() }, { putStringSet(key, it.toSet()) })
@Composable internal inline fun <reified E : Enum<E>> rememberBenchmarkEnum(key: String, initial: E): MutableState<E> {
    val persisted = rememberBenchmarkString(key, initial.name)
    return remember(key, persisted) {
        object : MutableState<E> {
            override var value: E
                get() = enumValues<E>().firstOrNull { it.name == persisted.value } ?: initial
                set(value) { persisted.value = value.name }
            override fun component1() = value
            override fun component2(): (E) -> Unit = { value = it }
        }
    }
}
