package com.sal7one.hearth.tts.chatterbox

import com.sal7one.hearth.tts.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ChatterboxModelsTest {
    @Test fun completeRawStackAndMandatoryWatermarkArePinnedSeparately() {
        assertEquals(1556285252L,ChatterboxCatalog.assets.sumOf{it.bytes})
        assertEquals(setOf("ar","en"),ChatterboxCatalog.languages)
        assertEquals(listOf("default"),ChatterboxCatalog.voices)
        assertEquals(37429684L,ChatterboxPerth.SOURCE_BYTES)
        assertTrue(ChatterboxCatalog.assets.all{it.sha256.matches(Regex("[a-f0-9]{64}"))})
    }
    @Test fun referenceNeverImpliesVerifiedGender() {
        val root=Files.createTempDirectory("chatterbox-voices").toFile()
        try {val engine=ChatterboxSpeechEngine(root);assertTrue(engine.voices.all{it.gender==VoiceGender.UNKNOWN});assertThrows(IllegalArgumentException::class.java){SpeechVoices.resolve(engine.voices,"ar",VoicePreference(gender=VoiceGender.FEMALE))};engine.close()}
        finally {root.deleteRecursively()}
    }
    @Test fun absentPerthPreventsReadinessAndIncompleteImportDoesNotPublish() {
        val root=Files.createTempDirectory("chatterbox-import").toFile()
        try {val models=ChatterboxModels(root);assertFalse(models.ready());assertThrows(IllegalStateException::class.java){models.verify()};assertThrows(IllegalArgumentException::class.java){models.importWeight(byteArrayOf(1,2).inputStream(),ChatterboxCatalog.assets.first())};assertTrue(root.listFiles()!!.isEmpty())}
        finally {root.deleteRecursively()}
    }
}
