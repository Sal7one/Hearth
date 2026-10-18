package com.sal7one.transiber.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val USER_PREFS_MIGRATED = "__hearth_user_preferences_migrated_v1"
private const val SEPARATOR = '\u001f'
internal fun userPreferenceKey(namespace: String, key: String): String {
    require(namespace.matches(Regex("[a-zA-Z0-9_-]+")) && !key.contains(SEPARATOR)) { "Invalid preference namespace or key" }
    return "$namespace$SEPARATOR$key"
}
internal fun MutablePreferences.putUserValue(key: String, value: Any) {
    when (value) {
        is String -> this[stringPreferencesKey(key)] = value
        is Boolean -> this[booleanPreferencesKey(key)] = value
        is Int -> this[intPreferencesKey(key)] = value
        is Long -> this[longPreferencesKey(key)] = value
        is Float -> this[floatPreferencesKey(key)] = value
        is Set<*> -> this[stringSetPreferencesKey(key)] = value.map { checkNotNull(it as? String) { "Invalid string preference set" } }.toSet()
        else -> error("Unsupported preference value: ${value.javaClass.simpleName}")
    }
}
internal fun migrateUserPreferences(current: Preferences, legacy: Map<String, Map<String, *>>): Preferences {
    if (current[booleanPreferencesKey(USER_PREFS_MIGRATED)] == true) return current
    return current.toMutablePreferences().apply {
        val existing = asMap().keys.map { it.name }.toSet()
        legacy.forEach { (namespace, values) -> values.forEach { (key, value) ->
            val qualified = userPreferenceKey(namespace, key)
            // A partially migrated or explicitly preconfigured DataStore wins.
            // Secret values remain the exact encrypted envelope bytes/string.
            if (qualified !in existing && value != null) putUserValue(qualified, value)
        } }
        this[booleanPreferencesKey(USER_PREFS_MIGRATED)] = true
    }
}
internal fun Preferences.userValues(namespace: String): Map<String, Any> {
    val prefix = "$namespace$SEPARATOR"
    return asMap().entries.filter { it.key.name.startsWith(prefix) }
        .associate { (key, value) -> key.name.removePrefix(prefix) to if (value is Set<*>) value.toSet() else value }
}

/** One ordered writer: immediate in-memory reads plus durable commit acknowledgements. */
internal class UserPreferenceData(
    private val dataStore: DataStore<Preferences>,
    initial: Preferences,
    scope: CoroutineScope,
    private val failure: MutableStateFlow<String?>,
) {
    private data class Write(var snapshot: Preferences, val complete: CompletableDeferred<Boolean>?)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val pending = ArrayDeque<Write>()
    private val mutable = MutableStateFlow(initial)
    val snapshots = mutable.asStateFlow()
    init {
        scope.launch {
            for (signal in writes) {
                while (true) {
                    val write = nextWrite() ?: break
                    val success = try {
                        dataStore.updateData { write.snapshot }
                        failure.value = null
                        true
                    } catch (error: Exception) {
                        failure.value = "Could not save Hearth settings: ${error.javaClass.simpleName}: ${error.message}"
                        false
                    }
                    write.complete?.complete(success)
                }
            }
        }
    }
    /** Whole immutable snapshots include all preceding queued edits, even after an I/O failure. */
    @Synchronized private fun nextWrite(): Write? = if (pending.isEmpty()) null else pending.removeFirst()
    @Synchronized fun change(namespace: String, clear: Boolean, updates: Map<String, Any?>,
        durable: Boolean = false): Pair<Set<String>, CompletableDeferred<Boolean>?> {
        val before = mutable.value.userValues(namespace)
        val after = before.toMutableMap().apply {
            if (clear) clear()
            updates.forEach { (key, value) -> if (value == null) remove(key) else put(key, if (value is Set<*>) value.toSet() else value) }
        }
        val snapshot = mutable.value.toMutablePreferences().apply {
            asMap().keys.filter { it.name.startsWith("$namespace$SEPARATOR") }.forEach { remove(it) }
            after.forEach { (key, value) -> putUserValue(userPreferenceKey(namespace, key), value) }
        }
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        mutable.value = snapshot
        val complete = if (durable) CompletableDeferred<Boolean>() else null
        // Slider drafts replace the last pending apply snapshot. Explicit
        // commits retain their own ordered acknowledgement and durable value.
        if (!durable && pending.lastOrNull()?.complete == null && pending.isNotEmpty()) pending.last().snapshot = snapshot
        else pending.addLast(Write(snapshot, complete))
        check(writes.trySend(Unit).isSuccess) { "Hearth settings writer is unavailable" }
        return changed to complete
    }
}
