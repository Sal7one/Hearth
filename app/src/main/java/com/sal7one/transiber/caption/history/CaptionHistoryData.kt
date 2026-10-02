package com.sal7one.transiber.caption.history

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SavedCaption(
    val id: Long,
    val created: Long,
    val original: String = "",
    val translation: String = "",
    val sourceLanguage: String? = null,
    val translationLanguage: String? = null,
    val engine: String = "",
    val source: String = "",
) {
    fun validate() {
        require(original.length <= 64000 && translation.length <= 64000) { "Caption history text exceeds 64000 characters. Saving stopped." }
        require((sourceLanguage?.length ?: 0) <= 128 && (translationLanguage?.length ?: 0) <= 128 && engine.length <= 1024 && source.length <= 128) {
            "Caption history metadata exceeds its size limit. Saving stopped."
        }
    }
}

internal data class CaptionSessionSummary(val id: String, val title: String, val created: Long,
    val updated: Long, val count: Int, val preview: String)
internal data class SavedCaptionSession(val summary: CaptionSessionSummary, val lines: List<SavedCaption>)
internal data class CaptionHistoryWrite(val sessionId: String, val started: Long, val consent: Long, val line: SavedCaption,
    val deletionVersion: Long = 0)

/** Partial runs are observed for identity only. Text is retained only once finalized.
 * Toggling off invalidates queued writes and late corrections, without backfilling on re-enable.
 * Boundaries (Clear/reconnect) start a fresh session but keep already accepted finals valid.
 */
internal class CaptionHistoryCapture(private val now: () -> Long = System::currentTimeMillis,
    private val deletions: () -> Long = { 0 }) {
    private var enabled = false
    private var closed = false
    private var consent = 0L
    private var started = now()
    private var sessionId = UUID.randomUUID().toString()
    private data class Owner(val consent: Long?, val sessionId: String, val started: Long, val deletionVersion: Long)
    private val owners = linkedMapOf<Long, Owner>()
    private var floor = Long.MIN_VALUE
    private var runCount = 0

    @Synchronized fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        consent++
        sessionId = UUID.randomUUID().toString()
        started = now()
        runCount = 0
    }
    @Synchronized fun boundary() {
        floor = maxOf(floor, owners.keys.maxOrNull() ?: floor)
        owners.clear()
        sessionId = UUID.randomUUID().toString()
        started = now()
        runCount = 0
        // Controller IDs are monotonic, including across Clear/reconnect.
        // floor deliberately survives so stale translations cannot acquire a new owner.
    }
    @Synchronized fun observe(line: SavedCaption, complete: Boolean): CaptionHistoryWrite? {
        if (closed || line.id <= floor) return null
        val owner = owners.getOrPut(line.id) {
            if (runCount++ >= MAX_TRACKED_LINES) {
                sessionId = UUID.randomUUID().toString(); started = now(); runCount = 1
            }
            Owner(if (enabled) consent else null, sessionId, started, deletions())
        }
        if (owners.size > MAX_TRACKED_LINES) {
            floor = maxOf(floor, owners.keys.first())
            owners.remove(owners.keys.first())
        }
        if (!complete || !enabled || owner.consent != consent || (line.original.isBlank() && line.translation.isBlank())) return null
        return CaptionHistoryWrite(owner.sessionId, owner.started, consent, line, owner.deletionVersion)
    }
    @Synchronized fun translation(id: Long, text: String, language: String?): CaptionHistoryWrite? {
        val owner = owners[id] ?: return null
        if (closed || !enabled || owner.consent != consent || text.isBlank()) return null
        return CaptionHistoryWrite(owner.sessionId, owner.started, consent,
            SavedCaption(id, now(), translation = text, translationLanguage = language), owner.deletionVersion)
    }
    @Synchronized fun mayWrite(write: CaptionHistoryWrite) = enabled && write.consent == consent
    @Synchronized fun close() { closed = true } // Accepted writes can drain after service destruction.
    companion object { const val MAX_TRACKED_LINES = 5000 }
}

/** Patch semantics: a translation/correction never erases the other available channel. */
internal fun SavedCaption.merge(patch: SavedCaption) = copy(
    original = patch.original.ifBlank { original }, translation = patch.translation.ifBlank { translation },
    sourceLanguage = patch.sourceLanguage ?: sourceLanguage,
    translationLanguage = patch.translationLanguage ?: translationLanguage,
    engine = patch.engine.ifBlank { engine }, source = patch.source.ifBlank { source },
)

internal fun SavedCaptionSession.toJson(): String = JSONObject().put("schemaVersion", 1)
    .put("id", summary.id).put("title", summary.title).put("created", summary.created)
    .put("updated", summary.updated).put("lines", JSONArray().apply { lines.forEach { line ->
        put(JSONObject().put("id", line.id).put("created", line.created)
            .put("original", line.original.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("translation", line.translation.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("sourceLanguage", line.sourceLanguage ?: JSONObject.NULL)
            .put("translationLanguage", line.translationLanguage ?: JSONObject.NULL)
            .put("engine", line.engine).put("source", line.source))
    } }).toString(2)

internal fun SavedCaptionSession.toText(originalLabel: String, translationLabel: String, unavailableLabel: String): String = buildString {
    appendLine(summary.title)
    lines.forEach { line ->
        appendLine()
        appendLine("$originalLabel${line.sourceLanguage?.let { " ($it)" }.orEmpty()}: ${line.original.ifBlank { unavailableLabel }}")
        if (line.translation.isNotBlank()) appendLine("$translationLabel${line.translationLanguage?.let { " ($it)" }.orEmpty()}: ${line.translation}")
    }
}
