package com.sal7one.transiber.conversation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Transactional, app-private history excluded from Android's automatic backup. No audio or keys. */
internal class ConversationStore(context: Context) : AutoCloseable {
    private val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "conversations.db"), null).apply {
        execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT NULL)")
    }

    @Synchronized fun save(session: ConversationSession) {
        val json = JSONObject().put("id", session.id).put("title", session.title)
            .put("first", session.first).put("second", session.second).put("created", session.created)
            .put("turns", JSONArray().apply { session.turns.forEach { t -> put(JSONObject()
                .put("id", t.id).put("speaker", t.speaker).put("source", t.source).put("target", t.target)
                .put("original", t.original).put("translation", t.translation).put("status", t.status.name)
                .put("error", t.error ?: JSONObject.NULL).put("created", t.created).put("route", t.route)) } })
        db.beginTransaction()
        try {
            db.insertWithOnConflict("sessions", null, ContentValues().apply {
                put("id", session.id); put("created", session.created); put("payload", json.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Unable to save conversation" } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun list(): List<ConversationSession> = db.rawQuery("SELECT payload FROM sessions ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) {
            val j = JSONObject(c.getString(0)); val turns = j.getJSONArray("turns")
            add(ConversationSession(j.getString("id"), j.getString("title"), j.getString("first"), j.getString("second"),
                List(turns.length()) { index -> turns.getJSONObject(index).let { t -> ConversationTurn(
                    t.getString("id"), t.getInt("speaker"), t.getString("source"), t.getString("target"),
                    t.getString("original"), t.getString("translation"), TurnStatus.valueOf(t.getString("status")),
                    if (t.isNull("error")) null else t.getString("error"), t.getLong("created"), t.optString("route")) } }, j.getLong("created")).interrupted())
        } }
    }
    @Synchronized fun delete(id: String) { db.delete("sessions", "id = ?", arrayOf(id)) }
    @Synchronized override fun close() = db.close()
}
