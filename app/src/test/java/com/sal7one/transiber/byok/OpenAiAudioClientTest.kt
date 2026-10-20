package com.sal7one.transiber.byok

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiAudioClientTest {
    @Test fun providerFailuresCannotBecomeEmptyTranscriptsAndContextPreservesArabic() {
        if (!ByokPolicy.FEATURE_BYOK) return
        MockWebServer().use { server ->
            val client = OpenAiAudioClient("fixture-key", server.url("/v1").toString(), "inclusionAI/Ming-3.0-asr-tiny")
            for (body in listOf("{}", "{\"text\":null}", "{\"text\":42}", "{\"error\":\"fixture-key failed\"}")) {
                server.enqueue(MockResponse().setBody(body))
                val error = assertThrows(ByokHttpException::class.java) { client.transcribe(byteArrayOf(1)) }
                assertFalse(error.message.orEmpty().contains("fixture-key"))
                server.takeRequest()
            }
            server.enqueue(MockResponse().setBody("{\"text\":\"مرحبا\"}"))
            assertEquals("مرحبا", client.transcribe(byteArrayOf(1), "ar", "مصطلحات عربية"))
            assertTrue(server.takeRequest().body.readUtf8().contains("مصطلحات عربية"))
        }
    }
    @Test fun optionalServerKeyIsOmittedAndOfflineBuildCannotSendAudio() {
        MockWebServer().use {server->
            if(!ByokPolicy.FEATURE_BYOK) {
                assertThrows(IllegalStateException::class.java) {OpenAiAudioClient("",server.url("/v1").toString(),"fixture").transcribe(byteArrayOf(1))}
                assertEquals(0,server.requestCount)
            } else for(key in listOf("","test-only-key")) {
                server.enqueue(MockResponse().setBody("{\"text\":\"Recognized fixture\"}"))
                assertEquals("Recognized fixture",OpenAiAudioClient(key,server.url("/v1").toString(),"fixture").transcribe(byteArrayOf(1,2)))
                val request=checkNotNull(server.takeRequest(1,TimeUnit.SECONDS))
                assertEquals("/v1/audio/transcriptions",request.path)
                assertEquals(key.takeIf {it.isNotBlank()}?.let {"Bearer $it"},request.getHeader("Authorization"))
            }
        }
    }
}
