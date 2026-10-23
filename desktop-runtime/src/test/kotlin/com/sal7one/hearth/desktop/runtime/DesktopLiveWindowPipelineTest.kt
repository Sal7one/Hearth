package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the ordinary desktop adapter and shared downstream stages, with no native model or recording. */
class DesktopLiveWindowPipelineTest {
    @Test
    fun idleGapsDoNotDelayLiveUtterancesOrFeedIdleAudioToTheTranslator() = runBlocking<Unit> {
        val uploadedPcmSizes = CopyOnWriteArrayList<Int>()
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stt") { request ->
            val body = request.requestBody.readAllBytes()
            val riff = body.indices.first { index -> index + 4 <= body.size && body.copyOfRange(index, index + 4).contentEquals("RIFF".toByteArray()) }
            uploadedPcmSizes += ByteBuffer.wrap(body, riff + 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val response = """{"text":"utterance ${calls.incrementAndGet()}"}""".toByteArray()
            request.sendResponseHeaders(200, response.size.toLong())
            request.responseBody.use { it.write(response) }
        }
        server.createContext("/translate") { request ->
            request.requestBody.readAllBytes()
            val response = """{"choices":[{"finish_reason":"stop","message":{"content":"translated"}}]}""".toByteArray()
            request.sendResponseHeaders(200, response.size.toLong())
            request.responseBody.use { it.write(response) }
        }
        server.start()
        val directory = Files.createTempDirectory("hearth-live-windows-")
        try {
            DesktopEngines(CredentialResolver { error("Loopback fixture must never resolve credentials") }, directory).use { engines ->
                var producedMs = 0
                val firstRecognitionAt = mutableListOf<Int>()
                val adapter = object : PipelineEngines by engines {
                    override suspend fun recognize(input: PipelineInput, selection: EngineSelection, sourceLanguage: String, emit: suspend (SpeechSegment) -> Unit) {
                        engines.recognize(input, selection, sourceLanguage) { firstRecognitionAt += producedMs; emit(it) }
                    }
                }
                val input = PipelineInput.AudioStream(flow {
                    for ((duration, value) in listOf(12000 to 0, 1000 to 1000, 600 to 0, 5000 to 0, 500 to 1000, 600 to 0)) {
                        repeat(duration / 100) {
                            val bytes = ByteArray(3200)
                            for (index in bytes.indices step 2) { bytes[index] = value.toByte(); bytes[index + 1] = (value shr 8).toByte() }
                            producedMs += 100
                            emit(PcmFrame(bytes))
                        }
                    }
                })
                val base = "http://127.0.0.1:${server.address.port}"
                val session = HearthPipeline(adapter, this).start(PipelineRequest(input, PipelineConfig(
                    speech = EngineSelection("openai-stt", endpoint = "$base/stt"),
                    translation = EngineSelection("openai-translation", endpoint = "$base/translate"),
                    sourceLanguage = "en", targetLanguage = "ar",
                )))
                val result = withTimeout(20000) { session.state.first { it.terminal } }
                assertEquals(PipelineStatus.COMPLETED, result.status, result.error)
                assertEquals(listOf(13600, 19700), firstRecognitionAt)
                assertEquals(listOf(1800 * 32, 1300 * 32), uploadedPcmSizes)
                assertEquals(listOf(11800L, 18400L), result.segments.map { it.startMs })
                assertEquals(listOf(13600L, 19700L), result.segments.map { it.endMs })
                assertEquals(2, result.segments.size)
                assertTrue(result.segments.all { it.final && it.translation == "translated" })
            }
        } finally { server.stop(0); directory.toFile().deleteRecursively() }
    }
}
