package com.sal7one.transiber.byok

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiAudioClientTest {
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
