package com.sal7one.transiber.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** User settings live in DataStore. Legacy APIs retain immediate reads and durable commit semantics. */
object UserPreferences {
    internal val migratedStores = setOf("hearth-appearance", "hearth-home", "conversation-options", "typed-translation",
        "reading-overlay", "voice-output", "camera-translate", "conversation-translators", "translation-browser",
        "byok_keys", "byok_cloud_config", "cloud_voice_v1")
    private val operationalStores = setOf("file_downloads", "model_registry", "caption_startup_diagnostics", "vosk_asset_model_anchor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableFailures = MutableStateFlow<String?>(null)
    val failures: StateFlow<String?> = mutableFailures.asStateFlow()
    private data class Instance(val data: UserPreferenceData, val stores: ConcurrentHashMap<String, Store> = ConcurrentHashMap())
    private val instances = ConcurrentHashMap<String, Instance>()
    private val dataStores = ConcurrentHashMap<String, DataStore<Preferences>>()

    /** Call on the launch loading screen. No settings are replaced with defaults on read failure. */
    suspend fun initialize(context: Context) { withContext(Dispatchers.IO) { instance(context) } }
    fun store(context: Context, namespace: String): SharedPreferences {
        require(namespace !in operationalStores) { "$namespace is operational state, not user preferences" }
        userPreferenceKey(namespace, "validate")
        val instance = instance(context)
        return instance.stores.getOrPut(namespace) { Store(namespace, instance.data) }
    }
    fun values(context: Context, namespace: String) = instance(context).data.snapshots
        .map { it.userValues(namespace) }.distinctUntilChanged()

    private fun instance(context: Context): Instance {
        val app = context.applicationContext
        val file = app.preferencesDataStoreFile("hearth_user_preferences")
        return instances[file.absolutePath] ?: synchronized(instances) {
            instances[file.absolutePath] ?: runBlocking(Dispatchers.IO) {
                val migration = object : DataMigration<Preferences> {
                    override suspend fun shouldMigrate(currentData: Preferences) = currentData[booleanPreferencesKey(USER_PREFS_MIGRATED)] != true
                    override suspend fun migrate(currentData: Preferences): Preferences {
                        val legacy = migratedStores.associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all }.toMutableMap()
                        // Download jobs stay operational state. Their chosen
                        // destination is a user preference and migrates alone.
                        legacy["download-location"] = app.getSharedPreferences("file_downloads", Context.MODE_PRIVATE).all
                            .filterKeys { it == "folder_uri" || it == "folder_label" }
                        return migrateUserPreferences(currentData, legacy)
                    }
                    override suspend fun cleanUp() {
                        // DataStore calls this only after its atomic migration is durable.
                        // Preserve any file whose cleanup fails; the marker prevents reimport.
                        migratedStores.forEach { name ->
                            check(app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) { "Could not finish settings migration for $name" }
                        }
                        check(app.getSharedPreferences("file_downloads", Context.MODE_PRIVATE).edit()
                            .remove("folder_uri").remove("folder_label").commit()) { "Could not finish download location migration" }
                    }
                }
                // Retain the same DataStore even when a disk read or migration
                // cleanup fails. A launch retry must never open this file with
                // a second active DataStore instance.
                val dataStore = dataStores.getOrPut(file.absolutePath) {
                    PreferenceDataStoreFactory.create(migrations = listOf(migration), scope = scope) { file }
                }
                val first = dataStore.data.first()
                Instance(UserPreferenceData(dataStore, first, scope, mutableFailures)).also { instances[file.absolutePath] = it }
            }
        }
    }
    private class Store(private val namespace: String, private val data: UserPreferenceData) : SharedPreferences {
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
        private fun value(key: String?) = data.snapshots.value.userValues(namespace)[requireNotNull(key)]
        override fun getAll(): MutableMap<String, *> = data.snapshots.value.userValues(namespace).toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = value(key)?.let { it as String } ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = value(key)?.let { @Suppress("UNCHECKED_CAST") (it as Set<String>).toMutableSet() } ?: defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int) = value(key)?.let { it as Int } ?: defValue
        override fun getLong(key: String?, defValue: Long) = value(key)?.let { it as Long } ?: defValue
        override fun getFloat(key: String?, defValue: Float) = value(key)?.let { it as Float } ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = value(key)?.let { it as Boolean } ?: defValue
        override fun contains(key: String?) = value(key) != null
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners += listener }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners -= listener }
        override fun edit(): SharedPreferences.Editor = Editor()
        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { updates[requireNotNull(key)] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            private fun save(durable: Boolean): kotlinx.coroutines.CompletableDeferred<Boolean>? {
                val (changed, complete) = data.change(namespace, clear, updates.toMap(), durable)
                val notify = Runnable { changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@Store, key) } } }
                if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else Handler(Looper.getMainLooper()).post(notify)
                return complete
            }
            override fun commit(): Boolean = runBlocking(Dispatchers.IO) { checkNotNull(save(true)).await() }
            override fun apply() { save(false) }
        }
    }
}
