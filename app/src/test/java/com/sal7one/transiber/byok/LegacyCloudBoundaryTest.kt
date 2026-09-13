package com.sal7one.transiber.byok

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class LegacyCloudBoundaryTest {
    @Test fun offlineEntryPointsRejectBeforeAnyRequest() {
        assumeFalse(ByokPolicy.FEATURE_BYOK)
        val attempts = listOf<() -> Unit>(
            { OpenAiAudioClient("synthetic fixture").transcribe(byteArrayOf()) },
            { OpenAiSpeechClient("synthetic fixture").speak("synthetic text") },
            { DeepgramStreamingClient("synthetic fixture").connect() },
            { AssemblyAiStreamingClient("synthetic fixture").connect() },
        )
        attempts.forEach { attempt ->
            val error = runCatching(attempt).exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("Cloud services are unavailable in the offline build", error?.message)
        }
    }
    @Test fun authenticatedHttpClientsDoNotFollowRedirects() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        MockWebServer().use { source ->
            MockWebServer().use { destination ->
                source.start(); destination.start()
                val url = source.url("/v1").toString()
                val attempts = listOf<() -> Unit>(
                    { OpenAiAudioClient("synthetic fixture", baseUrl = url).transcribe(byteArrayOf()) },
                    { OpenAiSpeechClient("synthetic fixture", baseUrl = url).speak("synthetic text") },
                )
                attempts.forEach { attempt ->
                    source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/redirect")).setBody("redirect fixture"))
                    destination.enqueue(MockResponse().setBody("{\"text\":\"unexpected redirected result\"}"))
                    val error = runCatching(attempt).exceptionOrNull()
                    assertTrue(error is ByokHttpException)
                    assertTrue(error?.message.orEmpty().contains("HTTP 307"))
                }
                assertEquals(2, source.requestCount)
                assertEquals(0, destination.requestCount)
            }
        }
    }
}
