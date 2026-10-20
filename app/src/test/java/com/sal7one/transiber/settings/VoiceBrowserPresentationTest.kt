package com.sal7one.transiber.settings

import org.junit.Assert.*
import org.junit.Test

class VoiceBrowserPresentationTest {
    @Test fun languagePickerFindsNativeLabelsByEnglishAndDiacriticInsensitiveNames() {
        assertTrue(voiceMenuMatches("Arabic", "ar", "العربية"))
        assertTrue(voiceMenuMatches("العَرَبِيَّة", "ar", "العربية"))
        assertTrue(voiceMenuMatches("francais", "fr", "Français"))
        assertTrue(voiceMenuMatches("English United", "en-US", "English (United States)"))
        assertFalse(voiceMenuMatches("Japanese", "ar", "العربية"))
    }

    @Test fun exactVoiceIdentifiersAndServerProvenanceRemainSearchable() {
        assertTrue(voiceMenuMatches("F3", "F3", "F3"))
        assertFalse(voiceMenuMatches("M3", "F3", "F3"))
        val card = VoiceBrowserCard("Example TTS", "Self-hosted Python", "Arabic and English", "Apache-2.0", "https://huggingface.co/publisher/model", setOf("ar", "en"))
        assertTrue(card.matches("English apache publisher"))
        assertFalse(card.matches("English japanese"))
    }
    @Test fun negatedCoverageDoesNotAdvertiseUnsupportedLanguagesAndPositiveLanguagesAreSearchable() {
        val qwen = VoiceBrowserCard("Qwen3-TTS 0.6B Base", "Self-hosted Python",
            "10 languages; no Arabic. Requires your reference WAV and matching text.", "Apache-2.0",
            "https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base", VoiceBrowserLanguages.qwen3TtsBase)
        assertEquals(10, qwen.languages.size)
        assertFalse(qwen.matches("Arabic"))
        assertFalse(qwen.matches("العربية"))
        assertFalse(qwen.matches("ar"))
        assertTrue(qwen.matches("Chinese"))
        assertTrue(qwen.matches("中文 Qwen"))
        assertTrue(qwen.matches("Portuguese Python"))
        assertFalse(qwen.matches("Hindi"))
    }

    @Test fun mingHostedCardsNeverAdvertiseArabic() {
        val ming = VoiceBrowserCard("Ming-3.0-tts-0.5B", "Self-hosted CUDA", "7 languages, no Arabic", "MIT", "https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B", VoiceBrowserLanguages.ming3Tts)
        assertEquals(7, ming.languages.size)
        assertTrue(ming.matches("English CUDA"))
        assertFalse(ming.matches("Arabic")); assertFalse(ming.matches("العربية"))
    }
    @Test fun fixedChatterboxCoverageAndUnspecifiedFishCheckpointRemainDistinct() {
        val chatterbox = VoiceBrowserCard("Chatterbox Multilingual V3", "Self-hosted Python", "23 languages",
            "MIT", "https://github.com/resemble-ai/chatterbox", VoiceBrowserLanguages.chatterboxMultilingualV3)
        assertEquals(23, chatterbox.languages.size)
        assertTrue(chatterbox.matches("Arabic Resemble"))
        assertTrue(chatterbox.matches("Swahili"))
        assertTrue(chatterbox.matches("中文"))
        assertFalse(chatterbox.matches("Vietnamese"))
        val fish = VoiceBrowserCard("Fish Speech", "Self-hosted official Fish server",
            "Languages depend on the deployed checkpoint; it may use English or Arabic voices.",
            "Fish Audio Research License", "https://github.com/fishaudio/fish-speech", emptySet())
        assertTrue(fish.matches("Fish server"))
        assertFalse(fish.matches("Arabic"))
        assertFalse(fish.matches("English"))
    }
}
