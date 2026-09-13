package com.sal7one.transiber.translation

import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TranslationHttpTransportTest {
    @Test fun offlineGateMakesNoRequest() = runBlocking {
        MockWebServer().use { server ->
            TranslationHttpTransport(enabled = false).use { transport ->
                val result = runCatching { transport.execute(Request.Builder().url(server.url("/")).build()) }
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("offline build"))
                assertEquals(0, server.requestCount)
            }
        }
    }
    @Test fun actualHttpErrorRetainsStatusAndBodyButRedactsKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"quota exhausted key=private-key\"}"))
            TranslationHttpTransport(enabled = true).use { transport ->
                val failure = runCatching { transport.execute(Request.Builder().url(server.url("/")).build(), "private-key") }.exceptionOrNull()
                assertEquals("HTTP 429: {\"error\":\"quota exhausted key=[redacted key]\"}", failure?.message)
            }
        }
    }
    @Test fun redirectsAreNeverFollowedWithCredentials() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/steal")))
            TranslationHttpTransport(enabled = true).use { transport ->
                val result = runCatching { transport.execute(Request.Builder().url(source.url("/translate")).header("Authorization", "private-key").build()) }
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 307"))
                assertEquals(1, source.requestCount); assertEquals(0, destination.requestCount)
            }
        } }
    }
    @Test fun cancellingCoroutineCancelsPendingHttpAndCloseRejectsNewCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed").setHeadersDelay(2, TimeUnit.SECONDS))
            val transport = TranslationHttpTransport(enabled = true)
            val job = launch { transport.execute(Request.Builder().url(server.url("/")).build()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            transport.close()
            val failure = runCatching { transport.execute(Request.Builder().url(server.url("/")).build()) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("closed"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun oversizedBodyCannotBecomeSuccessfulTranslation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            TranslationHttpTransport(enabled = true).use { transport ->
                val failure = runCatching { transport.execute(Request.Builder().url(server.url("/")).build()) }.exceptionOrNull()
                assertEquals("Cloud translation response exceeds 2 MiB", failure?.message)
            }
        }
    }
}
