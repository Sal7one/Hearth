package com.sal7one.transiber.voice

import org.junit.Assert.*
import org.junit.Test

class TtsPlaybackRequestTest {
    private fun request(text: String = "مرحبا", backend: String = "system", language: String = "ar-SA") =
        TtsPlaybackRequest(text, language, VoiceChoice(backend))

    @Test fun preservesTheRequestedLanguageRegionAndVoiceSnapshot() {
        val input = request().copy(choice = VoiceChoice("supertonic", "M4", 1.4f, 8), gender = VoiceGender.MALE, volume = .3f)
        val output = input.validated(false)
        assertEquals("ar-SA", output.language)
        assertEquals("M4", output.choice.voice)
        assertEquals(1.4f, output.choice.rate, 0f)
        assertEquals(.3f, output.volume, 0f)
    }
    @Test fun keepsLocalEnginesAvailableWithoutNetworkPermission() {
        assertEquals("system", request().validated(false).choice.backend)
        assertEquals("supertonic", request(backend = "supertonic").validated(false).choice.backend)
    }
    @Test(expected = IllegalStateException::class) fun rejectsServerSpeechInOfflineDistribution() {
        request(backend = "remote").validated(false)
    }
    @Test fun enforcesTextLimitsWithoutSilentlyTruncatingTheRequest() {
        assertEquals(5000, request("x".repeat(5000)).validated(false).text.length)
        assertThrows(IllegalArgumentException::class.java) { request("x".repeat(5001)).validated(false) }
        assertThrows(IllegalArgumentException::class.java) { request(" \n ").validated(false) }
    }
    @Test fun rejectsInvalidAudioControlsAndUnknownLanguages() {
        assertThrows(IllegalArgumentException::class.java) { request().copy(volume = Float.NaN).validated(false) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(volume = 1.01f).validated(false) }
        assertThrows(IllegalArgumentException::class.java) { request(language = "und").validated(false) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(choice = VoiceChoice(rate = Float.POSITIVE_INFINITY)).validated(false) }
    }
    @Test fun externalEngineAndVoiceIdentifiersAreBounded() {
        val valid = request().copy(systemEngine = "com.k2fsa.sherpa.onnx", systemVoice = "af_heart")
        assertEquals(valid, valid.validated(false))
        assertThrows(IllegalArgumentException::class.java) { request().copy(systemEngine = "file:///tmp/voice.so").validated(false) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(systemVoice = "x".repeat(201)).validated(false) }
    }
}
