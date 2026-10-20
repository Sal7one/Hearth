package com.sal7one.hearth.tts.oute

import com.sal7one.hearth.tts.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OuteModelsTest {
    @Test fun pinnedPackagePairsOneCombinedGeneratorDacWithItsReference() {
        assertEquals(1438441535L,OuteCatalog.assets.sumOf {it.bytes})
        assertEquals(listOf("oute-tts-1b.bin","en-female-1-neutral.json"),OuteCatalog.assets.map {it.filename})
        OuteCatalog.assets.forEach {assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")));assertFalse(it.filename.contains('/'));assertTrue(it.url.contains(if(it.filename.endsWith("json"))OuteCatalog.referenceRevision else OuteCatalog.revision))}
    }
    @Test fun referenceNameDoesNotClaimVerifiedCrossLingualGender() {
        val root=Files.createTempDirectory("oute-voices").toFile()
        try {
            val engine=OuteSpeechEngine(root)
            assertTrue(engine.voices.all {it.gender==VoiceGender.UNKNOWN})
            assertThrows(IllegalArgumentException::class.java){SpeechVoices.resolve(engine.voices,"ar",VoicePreference(gender=VoiceGender.FEMALE))}
            assertEquals(OuteCatalog.voices.single(),SpeechVoices.resolve(engine.voices,"ar-SA",VoicePreference()).voice.id)
            engine.close()
        }finally{root.deleteRecursively()}
    }
    @Test fun badImportPreservesExistingFileAndDeletesTemporaryOutput() {
        val root=Files.createTempDirectory("oute-import").toFile()
        try {
            val models=OuteModels(root);val asset=OuteCatalog.assets.last();val original=byteArrayOf(9,8,7)
            models.file(asset).writeBytes(original)
            assertFalse(models.ready())
            assertThrows(IllegalStateException::class.java){models.verify()}
            assertThrows(IllegalArgumentException::class.java){models.importWeight(byteArrayOf(1,2,3).inputStream(),asset)}
            assertArrayEquals(original,models.file(asset).readBytes())
            assertEquals(listOf(asset.filename),root.listFiles()!!.map {it.name})
        }finally{root.deleteRecursively()}
    }
}
