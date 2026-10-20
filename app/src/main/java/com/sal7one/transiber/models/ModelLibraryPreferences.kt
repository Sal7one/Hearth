package com.sal7one.transiber.models

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.preferences.UserPreferences

/** Event-time persistence survives navigating away before another composition/effect can run. */
internal class ModelBrowseState<T>(private val state: MutableState<T>,private val persist: (T)->Unit): MutableState<T> {
    override var value: T
        get()=state.value
        set(next) { if(state.value!=next) { state.value=next;persist(next) } }
    override fun component1()=value
    override fun component2(): (T)->Unit = { value=it }
}

/** Browsing state is separate from runtime selections; DataStore already coalesces pending writes. */
@Composable
internal fun rememberModelBrowseString(key: String, initial: String): MutableState<String> {
    val context = LocalContext.current
    val store = remember(context) { UserPreferences.store(context, "model-explorer") }
    val value = rememberSaveable(key) { mutableStateOf(store.getString(key, initial) ?: initial) }
    return remember(key,store,value) { ModelBrowseState(value) { next -> store.edit().putString(key,next).apply() } }
}

@Composable
internal fun rememberModelBrowseBoolean(key: String, initial: Boolean = false): MutableState<Boolean> {
    val context = LocalContext.current
    val store = remember(context) { UserPreferences.store(context, "model-explorer") }
    val value = rememberSaveable(key) { mutableStateOf(store.getBoolean(key, initial)) }
    return remember(key,store,value) { ModelBrowseState(value) { next -> store.edit().putBoolean(key,next).apply() } }
}
