package com.sal7one.hearth.tts.omnivoice

import com.sal7one.hearth.tts.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OmniVoiceModelsTest {
    @Test fun pinnedPackageIncludesGeneratorCodecAndExplicitArabicMapping() {
        assertEquals(945284608L, OmniVoiceCatalog.assets.sumOf { it.bytes })
        assertEquals("arb", OmniVoiceCatalog.nativeLanguage("ar"))
        assertEquals("arb", OmniVoiceCatalog.nativeLanguage("ar-SA"))
        assertEquals("en", OmniVoiceCatalog.nativeLanguage("en-US"))
        OmniVoiceCatalog.assets.forEach { assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}"))); assertFalse(it.filename.contains('/')) }
    }
    @Test fun designsNeverClaimVerifiedGenderOrMatchStrictGenderRequests() {
        val root=Files.createTempDirectory("omnivoice-voices").toFile()
        try {
            val engine=OmniVoiceSpeechEngine(root)
            assertEquals(OmniVoiceCatalog.designs, engine.voices.map { it.id })
            assertTrue(engine.voices.all { it.gender == VoiceGender.UNKNOWN })
            assertThrows(IllegalArgumentException::class.java) { SpeechVoices.resolve(engine.voices,"ar",VoicePreference(gender=VoiceGender.FEMALE)) }
            assertEquals("design-female",SpeechVoices.resolve(engine.voices,"ar",VoicePreference(id="design-female")).voice.id)
            engine.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun incompleteOrCorruptImportsNeverPublishAComponent() {
        val root=Files.createTempDirectory("omnivoice-import").toFile()
        try {
            val models=OmniVoiceModels(root)
            assertFalse(models.ready())
            assertThrows(IllegalStateException::class.java) { models.verify() }
            assertThrows(IllegalArgumentException::class.java) { models.importWeight(byteArrayOf(1,2,3).inputStream(),OmniVoiceCatalog.assets.first()) }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
