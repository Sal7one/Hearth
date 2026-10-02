package com.sal7one.transiber.byok

import com.sal7one.transiber.voice.VoiceGender
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CloudVoiceProtocolTest {
    @Test fun separateProviderScopesAndUnsafeEndpoints() {
        val c = CloudVoiceConfig()
        assertNotEquals(c.credentialScope(), CloudVoiceConfig(CloudVoiceProvider.OPENROUTER).credentialScope())
        assertNotEquals(c.copy(provider=CloudVoiceProvider.CUSTOM).credentialScope(), c.credentialScope())
        val custom = CloudVoiceConfig(CloudVoiceProvider.CUSTOM, "https://one.example/v1", "m", "v")
        assertNotEquals(custom.credentialScope(), custom.copy(endpoint="https://two.example/v1").credentialScope())
        assertEquals(custom.credentialScope(), custom.copy(endpoint="https://one.example/v1/").credentialScope())
        assertEquals(custom.credentialScope(), custom.copy(endpoint="HTTPS://ONE.example/v1/").credentialScope())
        for (bad in listOf("http://example.org", "https://key@example.org", "https://example.org?k=secret", "https://example.org#key"))
            assertThrows(IllegalArgumentException::class.java) { custom.copy(endpoint=bad).validated() }
        assertThrows(IllegalArgumentException::class.java) { c.copy(endpoint="https://other.example").validated() }
    }
    @Test fun genderDoesNotLeakOpenAiVoiceToOtherProviders() {
        val g = CloudVoiceConfig(CloudVoiceProvider.GEMINI)
        assertEquals("Kore", g.selectedVoice(VoiceGender.MALE))
        assertEquals("Puck", g.copy(maleVoice="Puck").selectedVoice(VoiceGender.MALE))
        assertEquals("echo", CloudVoiceConfig().selectedVoice(VoiceGender.MALE))
    }
    @Test fun openRouterRawAudioAndUnsupportedOptions() {
        val c = CloudVoiceConfig(CloudVoiceProvider.OPENROUTER)
        val request = CloudVoiceProtocol.speech(c.copy(speed=1.5f,instructions="not supported"), "مرحبا")
        assertEquals("https://openrouter.ai/api/v1/audio/speech", request.url)
        val body=JSONObject(request.body!!)
        assertEquals("mp3", body.getString("response_format")); assertEquals("Kore", body.getString("voice"))
        assertFalse(body.has("speed")); assertFalse(body.has("instructions"))
        assertArrayEquals(byteArrayOf(1,2,3), CloudVoiceProtocol.audio(c, byteArrayOf(1,2,3), "audio/mpeg").bytes)
    }
    @Test fun providerDefaultIsExplicitAndNeverInventsVoiceIds() {
        val c = CloudVoiceConfig(CloudVoiceProvider.OPENROUTER, model="fish-audio/s2.1-pro", voice="", providerDefaultVoice=true)
        assertFalse(JSONObject(CloudVoiceProtocol.speech(c,"hi").body!!).has("voice"))
        assertThrows(IllegalArgumentException::class.java) { c.copy(providerDefaultVoice=false).validated() }
        assertThrows(IllegalArgumentException::class.java) { CloudVoiceConfig(providerDefaultVoice=true).validated() }
    }
    @Test fun openAiModelVoiceAndStyle() {
        val body=JSONObject(CloudVoiceProtocol.speech(CloudVoiceConfig(instructions="Calm",speed=1.1f), "hello").body!!)
        assertEquals("Calm", body.getString("instructions")); assertEquals(1.1, body.getDouble("speed"), .0001)
        val legacy = CloudVoiceConfig(model="tts-1")
        assertFalse(CloudVoiceCatalog.voices(legacy).contains("coral"))
        assertFalse(JSONObject(CloudVoiceProtocol.speech(legacy.copy(voice="alloy"), "hi").body!!).has("instructions"))
    }
    @Test fun googleInteractionsRestNotSdkShortcutAndWavNotDoubleWrapped() {
        val c=CloudVoiceConfig(CloudVoiceProvider.GEMINI, instructions="Warm")
        val request=CloudVoiceProtocol.speech(c,"مرحبا")
        val body=JSONObject(request.body!!)
        assertEquals("x-goog-api-key",request.header); assertTrue(request.url.endsWith("/interactions"))
        assertFalse(body.getBoolean("store")); assertFalse(body.getBoolean("stream"))
        assertEquals("Warm",body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONArray("annotations").getJSONObject(0).getString("style"))
        val wav=WavEncoder.wrapPcm16(byteArrayOf(0,0,1,0),24000)
        val json="""{"steps":[{"type":"model_output","content":[{"type":"audio","mime_type":"audio/wav","data":"${Base64.getEncoder().encodeToString(wav)}"}]}]}"""
        assertArrayEquals(wav,CloudVoiceProtocol.audio(c,json.toByteArray(),"application/json").bytes)
        assertThrows(IllegalStateException::class.java) { CloudVoiceProtocol.audio(c,"""{"output_audio":{"data":"abc"}}""".toByteArray(),"application/json") }
    }
    @Test fun legacyGooglePcmIsWrappedWithExactSampleRateAndBytes() {
        val c=CloudVoiceConfig(CloudVoiceProvider.GEMINI,model="gemini-3.1-flash-tts-preview")
        assertTrue(CloudVoiceProtocol.speech(c,"Hi").url.endsWith(":generateContent"))
        val pcm=byteArrayOf(-1,-1,0,0,1,0)
        val raw="""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]}""".toByteArray()
        val result=CloudVoiceProtocol.audio(c,raw,"application/json").bytes
        assertEquals(pcm.size+44,result.size); assertArrayEquals(pcm,result.copyOfRange(44,result.size))
        assertArrayEquals(WavEncoder.encodePcm16(shortArrayOf(-1,0,1),24000),result)
        assertThrows(IllegalArgumentException::class.java) { WavEncoder.wrapPcm16(byteArrayOf(1),24000) }
    }
    @Test fun elevenWireAndSpeedBounds() {
        val c=CloudVoiceConfig(CloudVoiceProvider.ELEVENLABS,voice="account-voice",speed=1.2f)
        val r=CloudVoiceProtocol.speech(c,"hello")
        assertEquals("xi-api-key",r.header); assertTrue(r.url.endsWith("/account-voice?output_format=mp3_44100_128"))
        assertEquals("eleven_flash_v2_5",JSONObject(r.body!!).getString("model_id"))
        assertEquals(1.2, JSONObject(r.body!!).getJSONObject("voice_settings").getDouble("speed"), 0.0)
        assertThrows(IllegalArgumentException::class.java) { c.copy(speed=1.5f).validated() }
        assertThrows(IllegalArgumentException::class.java) { c.copy(voice="").validated() }
    }
    @Test fun compatibleJsonAudioKeepsExistingRootAndNestedForms() {
        val c = CloudVoiceConfig(CloudVoiceProvider.CUSTOM, "https://voice.example/v1", "m", "v")
        for (raw in listOf("""{"data":"AQID","format":"mp3"}""", """{"audio":{"data":"AQID","format":"mp3"}}"""))
            assertArrayEquals(byteArrayOf(1,2,3), CloudVoiceProtocol.audio(c,raw.toByteArray(),"application/json").bytes)
    }
    @Test fun malformedAudioNeverBecomesSuccess() {
        val c=CloudVoiceConfig()
        for(raw in listOf("{}"," {\"error\":{\"message\":\"blocked\"}}","{\"audio\":{\"data\":\"\"}}","{\"audio\":{\"data\":\"%%%\"}}"))
            assertThrows(Exception::class.java) { CloudVoiceProtocol.audio(c,raw.toByteArray(),"application/json") }
        assertThrows(IllegalStateException::class.java) { CloudVoiceProtocol.audio(c,"bad".toByteArray(),"text/html") }
        assertThrows(IllegalStateException::class.java) { CloudVoiceProtocol.audio(c,ByteArray(CloudVoiceProtocol.AUDIO_LIMIT+1),"audio/mpeg") }
    }
    @Test fun liveCatalogFiltersSpeechAndDeduplicates() {
        val raw="""{"data":[{"id":"stt","architecture":{"output_modalities":["text"]}},{"id":"voice","name":"Voice","architecture":{"output_modalities":["speech"]}},{"id":"voice","architecture":{"output_modalities":["speech"]}}]}"""
        assertEquals(listOf("voice" to "Voice"),CloudVoiceProtocol.models(CloudVoiceProvider.OPENROUTER,raw))
        assertEquals(listOf("tts" to "tts"),CloudVoiceProtocol.models(CloudVoiceProvider.ELEVENLABS,"""[{"model_id":"stt","can_do_text_to_speech":false},{"model_id":"tts","can_do_text_to_speech":true}]"""))
    }
    @Test fun voicePagesExposeGenderAndContinuationWithoutFetchingOtherUrls() {
        val page=CloudVoiceProtocol.voices("""{"voices":[{"voice_id":"v1","name":"Voice","labels":{"gender":"female"}}],"has_more":true,"next_page_token":"a&b"}""")
        assertEquals("Voice · female",page.voices.single().label)
        val request=CloudVoiceProtocol.voicesRequest(page.next)
        assertTrue(request.url.startsWith("https://api.elevenlabs.io/v2/voices?")); assertTrue(request.url.endsWith("a%26b"))
        assertThrows(IllegalStateException::class.java) { CloudVoiceProtocol.voices("""{"voices":[],"has_more":true}""") }
    }
}
