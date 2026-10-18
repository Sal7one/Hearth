package com.sal7one.transiber.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UserPreferenceDataTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var fail = false
        var writes = 0
        var started: CompletableDeferred<Unit>? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            ++writes; started?.complete(Unit); gate?.await()
            if (fail) throw IOException("disk is full")
            return transform(data.value).also { data.value = it }
        }
    }
    @Test fun migrationPreservesEveryTypeAndEncryptedEnvelopesWithoutOverridingDataStore() {
        val encrypted = "v2:KEYSTORE:opaque-iv:opaque-ciphertext"
        val prior = androidx.datastore.preferences.core.mutablePreferencesOf(stringPreferencesKey(userPreferenceKey("voice-output", "voice")) to "M3")
        val migrated = migrateUserPreferences(prior, mapOf(
            "voice-output" to mapOf("voice" to "F1", "rate" to 1.2f, "steps" to 5, "enabled" to true, "at" to 4L, "languages" to setOf("ar", "en")),
            "byok_keys" to mapOf("openai_api_key_v1" to encrypted)))
        assertEquals("M3", migrated.userValues("voice-output")["voice"])
        assertEquals(1.2f, migrated.userValues("voice-output")["rate"])
        assertEquals(5, migrated.userValues("voice-output")["steps"])
        assertEquals(4L, migrated.userValues("voice-output")["at"])
        assertEquals(true, migrated.userValues("voice-output")["enabled"])
        assertEquals(setOf("ar", "en"), migrated.userValues("voice-output")["languages"])
        assertEquals(encrypted, migrated.userValues("byok_keys")["openai_api_key_v1"])
        assertEquals(true, migrated[booleanPreferencesKey(USER_PREFS_MIGRATED)])
        assertSame(migrated, migrateUserPreferences(migrated, mapOf("voice-output" to mapOf("voice" to "F5"))))
    }
    @Test fun commitAcknowledgementWaitsForActualDurableWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemoryStore().apply { gate = CompletableDeferred(); started = CompletableDeferred() }
            val data = UserPreferenceData(store, emptyPreferences(), scope, MutableStateFlow(null))
            val done = checkNotNull(data.change("appearance", false, mapOf("theme" to "dark"), durable = true).second)
            store.started!!.await()
            assertFalse(done.isCompleted)
            assertEquals("dark", data.snapshots.value.userValues("appearance")["theme"])
            assertTrue(store.data.value.userValues("appearance").isEmpty())
            store.gate!!.complete(Unit)
            assertTrue(done.await())
            assertEquals("dark", store.data.value.userValues("appearance")["theme"])
        } finally { scope.cancel() }
    }
    @Test fun failedCommitReportsErrorAndNextWriteRetriesAllPendingChoices() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemoryStore().apply { fail = true }
            val failure = MutableStateFlow<String?>(null)
            val data = UserPreferenceData(store, emptyPreferences(), scope, failure)
            assertFalse(checkNotNull(data.change("voice", false, mapOf("secret" to "encrypted:unchanged"), true).second).await())
            assertTrue(failure.value.orEmpty().contains("disk is full"))
            store.fail = false
            assertTrue(checkNotNull(data.change("translation", false, mapOf("model" to "index-2b"), true).second).await())
            assertNull(failure.value)
            assertEquals("encrypted:unchanged", store.data.value.userValues("voice")["secret"])
            assertEquals("index-2b", store.data.value.userValues("translation")["model"])
        } finally { scope.cancel() }
    }
    @Test fun rapidApplyDraftsCoalesceWhileCommitKeepsItsOrderedSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemoryStore().apply { gate = CompletableDeferred(); started = CompletableDeferred() }
            val data = UserPreferenceData(store, emptyPreferences(), scope, MutableStateFlow(null))
            data.change("overlay", false, mapOf("size" to 0))
            store.started!!.await()
            repeat(2000) { data.change("overlay", false, mapOf("size" to it)) }
            val committed = checkNotNull(data.change("voice", false, mapOf("voice" to "F4"), true).second)
            store.gate!!.complete(Unit)
            assertTrue(committed.await())
            assertTrue("Draft snapshots must not grow with slider events", store.writes <= 3)
            assertEquals(1999, store.data.value.userValues("overlay")["size"])
            assertEquals("F4", store.data.value.userValues("voice")["voice"])
        } finally { scope.cancel() }
    }
    @Test fun concurrentIndependentNamespacesAndClearDoNotLoseChoices() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemoryStore()
            val data = UserPreferenceData(store, emptyPreferences(), scope, MutableStateFlow(null))
            (0 until 20).map { namespace -> async(Dispatchers.Default) {
                repeat(20) { key -> data.change("store-$namespace", false, mapOf("key-$key" to key)) }
            } }.awaitAll()
            assertTrue(checkNotNull(data.change("store-0", true, mapOf("retained" to "only"), true).second).await())
            assertEquals(mapOf("retained" to "only"), store.data.value.userValues("store-0"))
            for (namespace in 1 until 20) assertEquals(20, store.data.value.userValues("store-$namespace").size)
        } finally { scope.cancel() }
    }
}
