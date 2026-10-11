package com.sal7one.transiber.notes

import android.app.Application
import com.sal7one.common_jni.audio.Pcm16WaveWriter
import com.sal7one.common_jni.audio.PcmWave
import com.sal7one.transiber.caption.history.SavedCaption
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], manifest = Config.NONE, application = Application::class)
class VoiceNoteTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clean() { File(context.noBackupFilesDir, "voice-notes").deleteRecursively() }
    @Test fun patchesCorrectFinalOnceAndPreserveOriginal() {
        val transcript = NoteTranscript()
        transcript.observe(SavedCaption(8, 123, original = "السلام عليكم", sourceLanguage = "ar"))
        transcript.observe(SavedCaption(8, 125, translation = "Hello", translationLanguage = "en"))
        transcript.observe(SavedCaption(8, 126, translation = "Peace be upon you"))
        val line = transcript.snapshot().single()
        assertEquals("السلام عليكم", line.original)
        assertEquals("Peace be upon you", line.translation)
        assertEquals(123L, line.created)
    }
    @Test fun translatedOnlySourceIsNeverInvented() {
        val note = VoiceNote(title = "中文", status = NoteStatus.SAVED,
            lines = listOf(SavedCaption(1, 10, translation = "你好", translationLanguage = "zh", source = "MIC")))
        assertEquals(note, VoiceNote.parse(note.json()))
        assertEquals("", VoiceNote.parse(note.json()).lines.single().original)
        assertEquals("你好", note.text())
    }
    @Test fun privateStorageReopensRenamesAndDeletesAudioTogether() {
        val store = VoiceNoteStore(context)
        val note = VoiceNote(title = "Arabic voice", samples = 3, status = NoteStatus.SAVED)
        Pcm16WaveWriter(store.audio(note.id), 16000, 10).use { it.write(shortArrayOf(1, -2, 3)) }
        store.save(note)
        val reopened = VoiceNoteStore(context)
        assertEquals(note, reopened.list().single())
        reopened.save(note.copy(title = "Renamed"))
        assertEquals("Renamed", store.list().single().title)
        assertTrue(reopened.usedBytes() > 44)
        reopened.delete(note.id)
        assertTrue(store.list().isEmpty()); assertFalse(store.audio(note.id).exists())
    }
    @Test fun interruptedFileRecoversCompleteSamplesInsteadOfClaimingSuccess() {
        val store = VoiceNoteStore(context)
        val note = VoiceNote(title = "Interrupted")
        Pcm16WaveWriter(store.audio(note.id), 16000, 10).use { it.write(shortArrayOf(7, 8)) }
        // Simulate process death before the final length patch plus a torn final sample.
        java.io.RandomAccessFile(store.audio(note.id), "rw").use { f ->
            f.seek(4); f.write(byteArrayOf(36, 0, 0, 0)); f.seek(40); f.write(byteArrayOf(0, 0, 0, 0))
            f.seek(f.length()); f.write(9)
        }
        store.save(note)
        val recovered = store.recoverInterrupted().single()
        assertEquals(NoteStatus.INTERRUPTED, recovered.status)
        assertEquals(2L, recovered.samples)
        assertArrayEquals(shortArrayOf(7, 8), PcmWave.decode(store.audio(note.id).readBytes()).samples)
    }
    @Test fun pathAndTextLimitsRejectBadMetadataWithoutErasingExistingNote() {
        val store = VoiceNoteStore(context)
        val note = VoiceNote(title = "Keep me", status = NoteStatus.SAVED)
        store.save(note)
        assertThrows(IllegalArgumentException::class.java) { store.audio("../../escape") }
        assertThrows(IllegalArgumentException::class.java) { store.save(note.copy(title = "x".repeat(201))) }
        assertEquals(note, store.list().single())
    }
}
