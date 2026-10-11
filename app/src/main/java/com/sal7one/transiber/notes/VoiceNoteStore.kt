package com.sal7one.transiber.notes

import android.content.Context
import android.util.AtomicFile
import com.sal7one.common_jni.audio.Pcm16WaveWriter
import com.sal7one.transiber.caption.history.SavedCaption
import com.sal7one.transiber.caption.history.merge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class NoteStatus { RECORDING, SAVED, INTERRUPTED }
internal data class VoiceNote(val id: String = UUID.randomUUID().toString(), val title: String,
    val created: Long = System.currentTimeMillis(), val samples: Long = 0, val sampleRate: Int = 16000,
    val status: NoteStatus = NoteStatus.RECORDING, val lines: List<SavedCaption> = emptyList(),
    val error: String? = null) {
    fun json(): String = JSONObject().put("schemaVersion", 1).put("id", id).put("title", title)
        .put("created", created).put("samples", samples).put("sampleRate", sampleRate).put("status", status.name)
        .put("error", error ?: JSONObject.NULL).put("lines", JSONArray().apply { lines.forEach { l ->
            put(JSONObject().put("id", l.id).put("created", l.created).put("original", l.original)
                .put("translation", l.translation).put("sourceLanguage", l.sourceLanguage ?: JSONObject.NULL)
                .put("translationLanguage", l.translationLanguage ?: JSONObject.NULL).put("engine", l.engine).put("source", l.source))
        } }).toString(2)
    fun text() = lines.joinToString("\n\n") { listOf(it.original, it.translation).filter(String::isNotBlank).joinToString("\n") }
    companion object {
        fun parse(text: String): VoiceNote {
            val j = JSONObject(text)
            require(j.getInt("schemaVersion") == 1)
            val lines = j.getJSONArray("lines")
            require(lines.length() <= NoteTranscript.MAX_LINES)
            val note = VoiceNote(j.getString("id"), j.getString("title"), j.getLong("created"),
                j.getLong("samples"), j.getInt("sampleRate"), NoteStatus.valueOf(j.getString("status")),
                (0 until lines.length()).map { n -> val l = lines.getJSONObject(n)
                    SavedCaption(l.getLong("id"), l.getLong("created"), l.getString("original"), l.getString("translation"),
                        if (l.isNull("sourceLanguage")) null else l.getString("sourceLanguage"),
                        if (l.isNull("translationLanguage")) null else l.getString("translationLanguage"), l.getString("engine"), l.optString("source", ""))
                }, if (j.isNull("error")) null else j.getString("error"))
            validate(note); return note
        }
        fun validate(note: VoiceNote) {
            require(UUID.fromString(note.id).toString() == note.id && note.title.length in 1..200)
            require(note.sampleRate == 16000 && note.samples in 0..VoiceNoteStore.MAX_SAMPLES && (note.error?.length ?: 0) <= 4096)
            require(note.lines.size <= NoteTranscript.MAX_LINES && note.lines.map { it.id }.distinct().size == note.lines.size)
            note.lines.forEach { it.validate() }
            require(note.lines.sumOf { it.original.length + it.translation.length } <= NoteTranscript.MAX_CHARACTERS)
        }
    }
}

/** Stable final IDs preserve translation patches and corrections without depending on display tail. */
internal class NoteTranscript {
    private val lines = linkedMapOf<Long, SavedCaption>()
    @Synchronized fun observe(patch: SavedCaption): List<SavedCaption> {
        patch.validate()
        val merged = lines[patch.id]?.merge(patch) ?: patch
        check(lines.size + (if (lines.containsKey(patch.id)) 0 else 1) <= MAX_LINES) { "Voice note transcript reached its line limit" }
        val characters = lines.values.sumOf { it.original.length + it.translation.length } -
            (lines[patch.id]?.let { it.original.length + it.translation.length } ?: 0) + merged.original.length + merged.translation.length
        check(characters <= MAX_CHARACTERS) { "Voice note transcript reached its text limit" }
        lines[patch.id] = merged
        return lines.values.toList()
    }
    @Synchronized fun snapshot() = lines.values.toList()
    companion object { const val MAX_LINES = 5000; const val MAX_CHARACTERS = 256000 }
}

/** App-private local storage; methods run on the caller's serialized IO dispatcher. No sync/login tokens. */
internal class VoiceNoteStore(context: Context) {
    private val folder = File(context.noBackupFilesDir, "voice-notes").apply { check(isDirectory || mkdirs()) }
    fun audio(id: String): File { require(UUID.fromString(id).toString() == id); return File(folder, "$id.wav") }
    fun usedBytes() = folder.listFiles()?.sumOf { it.length() } ?: 0L
    fun capacitySamples(): Long {
        check((folder.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0) < MAX_NOTES) { "Voice-note storage reached 100 notes. Export or delete a note first." }
        val bytes = minOf(MAX_BYTES - usedBytes() - METADATA_RESERVE, folder.usableSpace - FREE_RESERVE)
        check(bytes >= 32000) { "Voice-note storage is full. Export or delete recordings first." }
        return minOf(MAX_SAMPLES, bytes / 2)
    }
    fun save(note: VoiceNote) {
        VoiceNote.validate(note)
        val bytes = note.json().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA) { "Voice-note metadata exceeds its storage limit" }
        val atomic = AtomicFile(File(folder, "${note.id}.json"))
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) }
        catch (t: Throwable) { atomic.failWrite(out); throw t }
    }
    fun load(id: String): VoiceNote {
        audio(id) // validate identity before constructing a metadata path
        return read(File(folder, "$id.json"))
    }
    private fun read(file: File): VoiceNote {
        require(file.length() <= MAX_METADATA)
        return AtomicFile(file).openRead().use { stream ->
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                require(output.size() <= MAX_METADATA)
            }
            VoiceNote.parse(String(output.toByteArray(), Charsets.UTF_8))
        }
    }
    fun list(previewsOnly: Boolean = false): List<VoiceNote> = folder.listFiles { f -> f.name.endsWith(".json") }?.map { file ->
        val note = read(file)
        if (previewsOnly) note.copy(lines = note.lines.take(1)) else note
    }?.sortedByDescending { it.created } ?: emptyList()
    fun recoverInterrupted(): List<VoiceNote> = list(previewsOnly = true).map { preview ->
        val note = if (preview.status == NoteStatus.RECORDING) load(preview.id) else preview
        if (note.status != NoteStatus.RECORDING) note else {
            val recovered = try { note.copy(samples = Pcm16WaveWriter.recoverInterrupted(audio(note.id), note.sampleRate, MAX_SAMPLES),
                status = NoteStatus.INTERRUPTED, error = "Recording interrupted; complete on-disk audio recovered") }
            catch (e: Exception) { note.copy(status = NoteStatus.INTERRUPTED, error = (e.message ?: e.javaClass.simpleName).take(4096)) }
            save(recovered); recovered.copy(lines = recovered.lines.take(1))
        }
    }
    fun delete(id: String) {
        check(!audio(id).exists() || audio(id).delete()) { "Could not delete the recording" }
        AtomicFile(File(folder, "$id.json")).delete()
    }
    companion object {
        const val MAX_NOTES = 100
        const val MAX_BYTES = 512L * 1024 * 1024
        const val MAX_SAMPLES = 16000L * 60 * 60
        const val MAX_METADATA = 2 * 1024 * 1024
        private const val METADATA_RESERVE = 2L * 1024 * 1024
        private const val FREE_RESERVE = 8L * 1024 * 1024
    }
}
