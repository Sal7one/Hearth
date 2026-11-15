package com.sal7one.transiber.byok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SonioxStreamingClientTest {
    @Test fun sendsCredentialsOnlyInTheUpgradeAndUsesV6ForTranslatedSpeech() {
        val request = SonioxStreamingClient.upgradeRequest("fixture-key")
        assertEquals("https://stt-rt.soniox.com/transcribe-websocket", request.url.toString())
        assertEquals("Bearer fixture-key", request.header("Authorization"))

        val config = SonioxStreamingClient.startMessage("ar", "en")
        assertFalse(config.has("api_key"))
        assertEquals("stt-rt-v6", config.getString("model"))
        assertEquals("ar", config.getJSONArray("language_hints").getString(0))
        assertEquals("en", config.getJSONObject("translation").getString("target_language"))
        assertFalse(SonioxStreamingClient.startMessage("auto", null).has("language_hints"))
    }
}
