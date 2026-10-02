package com.sal7one.transiber.caption.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** App-private text only, outside automatic backup. All SQLite work is serialized on IO.
 * No automatic eviction: explicit limits stop saving, preserving older sessions for export.
 * Tombstones prevent a queued/late result from resurrecting a session the user deleted.
 */
internal class CaptionHistoryRepository private constructor(context: Context) {
    private val directory = context.applicationContext.noBackupFilesDir
    private val exportDirectory = File(context.applicationContext.cacheDir, "caption-exports")
    private val mutex = Mutex()
    private var database: SQLiteDatabase? = null
    private val deletions = AtomicLong()
    val deletionVersion get() = deletions.get()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val savingError = failure.asStateFlow()
    fun reportSavingError(message: String) { failure.value = message }
    fun clearSavingError() { failure.value = null }
    private fun db(): SQLiteDatabase = database ?: SQLiteDatabase.openOrCreateDatabase(
        File(directory, "caption-history.db"), null).apply {
        execSQL("PRAGMA secure_delete=ON")
        execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        execSQL("CREATE TABLE IF NOT EXISTS lines (session TEXT NOT NULL, id INTEGER NOT NULL, created INTEGER NOT NULL, original TEXT NOT NULL, translation TEXT NOT NULL, source_lang TEXT, target_lang TEXT, engine TEXT NOT NULL, source TEXT NOT NULL, bytes INTEGER NOT NULL, PRIMARY KEY(session,id))")
        execSQL("CREATE TABLE IF NOT EXISTS usage (id INTEGER PRIMARY KEY CHECK(id=1), count INTEGER NOT NULL, bytes INTEGER NOT NULL)")
        execSQL("INSERT OR IGNORE INTO usage SELECT 1,COUNT(*),COALESCE(SUM(bytes),0) FROM lines")
        execSQL("PRAGMA user_version=1")
        database = this
    }
    private suspend fun <T> locked(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block(db()) } }

    suspend fun record(write: CaptionHistoryWrite, permitted: () -> Boolean) = locked { db ->
        fun allowed() = permitted() && write.deletionVersion == deletionVersion
        if (!allowed()) return@locked
        write.line.validate()
        db.beginTransaction()
        var saved = false
        try {
            val exists = db.rawQuery("SELECT deleted FROM sessions WHERE id=?", arrayOf(write.sessionId)).use {
                if (it.moveToFirst()) { if (it.getInt(0) != 0) return@locked; true } else false
            }
            val old = db.rawQuery("SELECT * FROM lines WHERE session=? AND id=?", arrayOf(write.sessionId, write.line.id.toString())).use {
                if (it.moveToFirst()) it.caption() else null
            }
            val line = old?.merge(write.line) ?: write.line
            val bytes = line.original.toByteArray(Charsets.UTF_8).size + line.translation.toByteArray(Charsets.UTF_8).size
            val previousBytes = old?.let { it.original.toByteArray(Charsets.UTF_8).size + it.translation.toByteArray(Charsets.UTF_8).size } ?: 0
            val totals = db.rawQuery("SELECT count,bytes FROM usage WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
            check(totals.second - previousBytes + bytes <= MAX_TEXT_BYTES && (old != null || totals.first < MAX_LINES)) {
                "Caption history is full (50000 lines or 64 MiB of text). Export or delete saved sessions to continue."
            }
            if (!exists) {
                check(db.rawQuery("SELECT COUNT(*) FROM sessions WHERE deleted=0", null).use { it.moveToFirst(); it.getInt(0) } < MAX_SESSIONS) {
                    "Caption history is full (200 sessions). Export or delete saved sessions to continue."
                }
                check(db.insertOrThrow("sessions", null, ContentValues().apply {
                    put("id", write.sessionId); put("title", line.engine); put("created", write.started); put("updated", line.created)
                }) != -1L)
            }
            val values = ContentValues().apply {
                put("session", write.sessionId); put("id", line.id); put("created", line.created)
                put("original", line.original); put("translation", line.translation)
                put("source_lang", line.sourceLanguage); put("target_lang", line.translationLanguage)
                put("engine", line.engine); put("source", line.source); put("bytes", bytes)
            }
            check(db.insertWithOnConflict("lines", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Unable to save caption history" }
            db.execSQL("UPDATE usage SET count=count+?,bytes=bytes+? WHERE id=1", arrayOf(if (old == null) 1 else 0, bytes - previousBytes))
            db.execSQL("UPDATE sessions SET updated=MAX(updated,?) WHERE id=?", arrayOf(System.currentTimeMillis(), write.sessionId))
            // A toggle/delete while IO was in progress rolls this entire transaction back.
            if (allowed()) { db.setTransactionSuccessful(); saved = true }
        } finally { db.endTransaction() }
        if (saved) { failure.value = null; changes.value++ }
    }

    suspend fun list(): List<CaptionSessionSummary> = locked { db ->
        db.rawQuery("SELECT s.id,s.title,s.created,s.updated,COUNT(l.id),COALESCE((SELECT CASE WHEN original='' THEN translation ELSE original END FROM lines WHERE session=s.id ORDER BY id DESC LIMIT 1),'') FROM sessions s JOIN lines l ON s.id=l.session WHERE s.deleted=0 GROUP BY s.id ORDER BY s.updated DESC LIMIT 200", null).use { c ->
            buildList { while (c.moveToNext()) add(CaptionSessionSummary(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getString(5))) }
        }
    }
    suspend fun session(id: String): SavedCaptionSession = locked { db ->
        val summary = db.rawQuery("SELECT id,title,created,updated FROM sessions WHERE id=? AND deleted=0", arrayOf(id)).use {
            check(it.moveToFirst()) { "Saved caption session no longer exists" }
            CaptionSessionSummary(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), 0, "")
        }
        val lines = db.rawQuery("SELECT * FROM lines WHERE session=? ORDER BY id", arrayOf(id)).use { c -> buildList { while (c.moveToNext()) add(c.caption()) } }
        SavedCaptionSession(summary.copy(count = lines.size), lines)
    }
    suspend fun rename(id: String, title: String) = locked { db ->
        require(title.isNotBlank() && title.length <= 120)
        check(db.update("sessions", ContentValues().apply { put("title", title.trim()) }, "id=? AND deleted=0", arrayOf(id)) == 1) { "Saved caption session no longer exists" }
        changes.value++
    }
    suspend fun delete(id: String? = null) {
        // Invalidate all pre-delete-all ownership, including sessions still only in the IO queue.
        if (id == null) deletions.incrementAndGet()
        locked { db ->
            db.beginTransaction()
            try {
                if (id == null) {
                    db.execSQL("UPDATE sessions SET deleted=1")
                    db.delete("lines", null, null)
                    db.execSQL("UPDATE usage SET count=0,bytes=0 WHERE id=1")
                } else {
                    val removed = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM lines WHERE session=?", arrayOf(id)).use {
                        it.moveToFirst(); it.getLong(0) to it.getLong(1)
                    }
                    db.execSQL("UPDATE sessions SET deleted=1 WHERE id=?", arrayOf(id))
                    db.delete("lines", "session=?", arrayOf(id))
                    db.execSQL("UPDATE usage SET count=count-?,bytes=bytes-? WHERE id=1", arrayOf(removed.first, removed.second))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            changes.value++
            // Revoke app-private share copies as well. User-created external exports remain theirs.
            exportDirectory.listFiles()?.filter { id == null || it.name.startsWith("hearth-captions-$id-") }?.forEach {
                check(it.delete() || !it.exists()) { "Unable to remove caption share file: ${it.name}" }
            }
        }
    }
    private fun Cursor.caption(): SavedCaption {
        fun text(key: String) = getString(getColumnIndexOrThrow(key))
        return SavedCaption(getLong(getColumnIndexOrThrow("id")), getLong(getColumnIndexOrThrow("created")),
            text("original"), text("translation"), text("source_lang"), text("target_lang"), text("engine"), text("source"))
    }
    companion object {
        const val MAX_SESSIONS = 200
        const val MAX_LINES = 50000
        const val MAX_TEXT_BYTES = 64L * 1024 * 1024
        @Volatile private var instance: CaptionHistoryRepository? = null
        fun get(context: Context): CaptionHistoryRepository = instance ?: synchronized(this) {
            instance ?: CaptionHistoryRepository(context).also { instance = it }
        }
    }
}
