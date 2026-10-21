package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the exact self-hosted Hearth Ming voice contract without a CUDA checkpoint. */
class DesktopMingTtsTest {
    @Test
    fun selfHostedMingSpeechContractIsUsed() = runBlocking<Unit> {
        var requestBody = ""
        var authorization = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/speech") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val wav = wav()
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }
        }
        server.start()
        val directory = Files.createTempDirectory("hearth-ming-")
        try {
            val selection = EngineSelection(
                "ming-tts", "inclusionAI/Ming-3.0-tts-0.5B", "",
                endpoint = "http://127.0.0.1:${server.address.port}/speech", credentialId = "ming",
            )
            val audio = DesktopEngines(CredentialResolver { "test-token" }, directory).use { engines ->
                val descriptor = engines.capabilities().single { it.id == "ming-tts" }
                assertEquals(setOf("zh", "en", "de", "es", "fr", "ko", "ja"), descriptor.languages)
                engines.synthesize("Hello from Hearth.", selection, "en", "US_Emma")
            }
            assertTrue(Files.size(java.nio.file.Path.of(audio.path)) > 44)
            assertEquals("Bearer test-token", authorization)
            assertTrue(requestBody.contains("\"text\":\"Hello from Hearth.\""), requestBody)
            assertTrue(requestBody.contains("\"model\":\"inclusionAI/Ming-3.0-tts-0.5B\""), requestBody)
            assertTrue(requestBody.contains("\"language\":\"en\""), requestBody)
            assertTrue(requestBody.contains("\"voice\":\"US_Emma\""), requestBody)
        } finally {
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun mingRejectsUnpublishedLanguagesAndOversizeText() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("hearth-ming-limits-")
        val selection = EngineSelection(
            "ming-tts", "inclusionAI/Ming-3.0-tts-0.5B", "",
            endpoint = "http://127.0.0.1:9/speech", credentialId = "ming",
        )
        try {
            DesktopEngines(CredentialResolver { "test-token" }, directory).use { engines ->
                assertFailsWith<IllegalArgumentException> { engines.synthesize("مرحبا", selection, "ar", "US_Emma") }
                assertFailsWith<IllegalArgumentException> { engines.synthesize("x".repeat(301), selection, "en", "US_Emma") }
                assertFailsWith<IllegalArgumentException> { engines.synthesize("hello", selection, "en", "not-a-ming-voice") }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun wav(): ByteArray {
        val samples = 2_400
        val data = samples * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data)
        for (index in 0 until samples) buffer.putShort((kotlin.math.sin(index / 12.0) * 4000).toInt().toShort())
        return buffer.array()
    }
}
