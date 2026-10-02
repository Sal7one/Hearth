@file:Suppress("DEPRECATION") // Pin native SQLite explicitly; never fall back to a legacy SQL shadow.

package com.sal7one.transiber.caption.history

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Native SQLite through Android's framework API, not a mocked execSQL or desktop SQL probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CaptionHistorySqliteTest {
    private val repository get() = CaptionHistoryRepository.get(RuntimeEnvironment.getApplication())
    private val databaseFile get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "caption-history.db")

    // Keep lifetime inspection in tests; do not add a public database/close API to the app singleton.
    private fun cachedDatabase(): SQLiteDatabase? = ReflectionHelpers.getField(repository, "database")
    private fun closeDatabase() {
        cachedDatabase()?.close()
        ReflectionHelpers.setField(repository, "database", null)
    }
    @Before fun startFreshDatabase() {
        check(!databaseFile.exists() || SQLiteDatabase.deleteDatabase(databaseFile))
    }
    @After fun tearDown() {
        closeDatabase()
        // Robolectric reuses a sandbox for a class; isolate the app singleton between test cases.
        ReflectionHelpers.setStaticField(CaptionHistoryRepository::class.java, "instance", null)
    }
    private fun write(session: String = "session", original: String = "Hello", translation: String = "",
        deletionVersion: Long = repository.deletionVersion) = CaptionHistoryWrite(session, 100, 1,
        SavedCaption(1, 101, original, translation, "en", "ar".takeIf { translation.isNotEmpty() }, "Nemotron", "MIC"), deletionVersion)
    private fun usage(): Pair<Long, Long> = cachedDatabase()!!.rawQuery("SELECT count,bytes FROM usage WHERE id=1", null).use {
        assertTrue(it.moveToFirst()); it.getLong(0) to it.getLong(1)
    }

    @Test fun emptyFreshHistoryOpensWithoutSqliteError() = runBlocking {
        assertTrue(repository.list().isEmpty())
        assertTrue(repository.list().isEmpty())
        val db = cachedDatabase()!!
        assertEquals(1, db.version)
        db.rawQuery("PRAGMA secure_delete", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        assertEquals(0L to 0L, usage())
    }

    @Test fun saveAndTranslationPatchSurviveDatabaseReopen() = runBlocking {
        val original = "Hello 你好 😀"
        val translation = "مرحبا"
        repository.record(write(original = original)) { true }
        repository.record(write(original = "", translation = translation)) { true }
        repository.rename("session", "Conversation")
        closeDatabase() // Exercise lazy reopen of the persisted database.
        val summary = repository.list().single()
        assertEquals("Conversation", summary.title)
        assertEquals(1, summary.count)
        assertEquals(original, summary.preview)
        val saved = repository.session("session").lines.single()
        assertEquals(original, saved.original)
        assertEquals(translation, saved.translation)
        assertEquals("en", saved.sourceLanguage)
        assertEquals("ar", saved.translationLanguage)
        assertEquals(1L to (original.toByteArray().size + translation.toByteArray().size).toLong(), usage())
    }

    @Test fun translatedOnlyHistoryKeepsOriginalUnavailable() = runBlocking {
        repository.record(write(original = "", translation = "ترجمة فقط").let {
            it.copy(line = it.line.copy(sourceLanguage = null, engine = "GPT Translate"))
        }) { true }
        assertEquals("ترجمة فقط", repository.list().single().preview)
        val saved = repository.session("session").lines.single()
        assertEquals("", saved.original)
        assertNull(saved.sourceLanguage)
        assertEquals("ترجمة فقط", saved.translation)
    }

    @Test fun deletePreservesTombstonesAndDeleteAllInvalidatesPendingSaves() = runBlocking {
        val first = write()
        repository.record(first) { true }
        repository.delete("session")
        repository.record(first.copy(line = first.line.copy(translation = "late"))) { true }
        assertTrue(repository.list().isEmpty())
        assertEquals(0L to 0L, usage())
        val pending = write(session = "not-yet-saved")
        repository.delete()
        repository.record(pending) { true }
        assertTrue(repository.list().isEmpty())
        repository.record(write(session = "new-session")) { true }
        assertEquals("new-session", repository.list().single().id)
        repository.delete()
        closeDatabase()
        assertTrue(repository.list().isEmpty())
        assertEquals(0L to 0L, usage())
    }

    @Test fun revokedConsentRollsBackSessionLineAndCounters() = runBlocking {
        var checks = 0
        repository.record(write()) { ++checks == 1 }
        assertEquals(2, checks)
        assertTrue(repository.list().isEmpty())
        assertEquals(0L to 0L, usage())
    }

    @Test fun initializationFailureStaysVisibleAndCanBeRetriedAfterRepair() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use {
            it.execSQL("CREATE TABLE usage (wrong_column INTEGER)")
        }
        val failure = assertThrows(SQLiteException::class.java) { runBlocking { repository.list() } }
        assertTrue(failure.message.orEmpty().contains("columns"))
        assertNull(cachedDatabase())
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { it.execSQL("DROP TABLE usage") }
        assertTrue(repository.list().isEmpty())
        repository.record(write()) { true }
        assertEquals("Hello", repository.session("session").lines.single().original)
    }
}
