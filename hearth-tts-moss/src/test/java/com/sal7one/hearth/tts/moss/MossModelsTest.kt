package com.sal7one.hearth.tts.moss

import com.sal7one.hearth.tts.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MossModelsTest {
    @Test fun completePinnedPackageIncludesReferenceCodesAndStereoCodec() {
        assertEquals(717868602L,MossCatalog.assets.sumOf { it.bytes })
        assertEquals(18,MossCatalog.voices.size)
        assertTrue("Ava" in MossCatalog.voices && "Junhao" in MossCatalog.voices)
        assertTrue("ar" in MossCatalog.languages && "en" in MossCatalog.languages)
        MossCatalog.assets.forEach { assertTrue(it.bytes>0);assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")));assertFalse(it.filename.contains('/'));assertTrue(it.url.startsWith("https://huggingface.co/")) }
    }
    @Test fun referenceNamesNeverImplyVerifiedGender() {
        val root=Files.createTempDirectory("moss-voices").toFile()
        try { val engine=MossSpeechEngine(root);assertTrue(engine.voices.all { it.gender==VoiceGender.UNKNOWN });assertThrows(IllegalArgumentException::class.java) { SpeechVoices.resolve(engine.voices,"ar",VoicePreference(gender=VoiceGender.MALE)) };engine.close() }
        finally { root.deleteRecursively() }
    }
    @Test fun incompleteImportDoesNotPublishAssets() {
        val root=Files.createTempDirectory("moss-import").toFile()
        try { val models=MossModels(root);assertFalse(models.ready());assertThrows(IllegalStateException::class.java) { models.verify() };assertThrows(IllegalArgumentException::class.java) { models.importWeight(byteArrayOf(1,2,3).inputStream(),MossCatalog.assets.first()) };assertTrue(root.listFiles()!!.isEmpty()) }
        finally { root.deleteRecursively() }
    }
}
