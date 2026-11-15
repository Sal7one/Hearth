package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.desktop.data.DesktopSettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The worker entry used by the smoke: the real [ServiceWorker], with real engines, in its own process. */
fun main(arguments: Array<String>) {
    ServiceWorker.run(Path.of(arguments[0]), Path.of(arguments[1]))
    kotlin.system.exitProcess(0)
}

/**
 * Owner opt-in only: real models through the whole service on this computer, with the engines
 * in a separate worker process. No download, recording or playback. Set HEARTH_SERVICE_SMOKE=1,
 * HEARTH_SMOKE_QWEN_DIR (the Qwen3-ASR directory), HEARTH_SMOKE_WAV (speech to transcribe),
 * HEARTH_SMOKE_CT2_DIR (the QuickMT English to Arabic directory) and HEARTH_SUPERTONIC_SMOKE_ROOT,
 * then run `:desktop-service:test --tests "*DesktopServiceRealSmokeTest*"`.
 */
class DesktopServiceRealSmokeTest {
    private fun path(name: String) = Path.of(requireNotNull(System.getenv(name)) { "$name is required" }).toRealPath()

    /** The setup an owner would save in the app: Qwen3-ASR, QuickMT English to Arabic and Supertonic, all on CPU. */
    private fun savedSetup(): PipelineConfig {
        val qwen = path("HEARTH_SMOKE_QWEN_DIR"); val quickmt = path("HEARTH_SMOKE_CT2_DIR"); val supertonic = path("HEARTH_SUPERTONIC_SMOKE_ROOT")
        val pins = Files.list(quickmt).use { files -> files.filter { Files.isRegularFile(it) }.toList() }.associate { "sha256.${it.fileName}" to sha256Hex(it) }
        return PipelineConfig(
            speech = EngineSelection("qwen3-asr-local", "qwen3-asr-0.6b", qwen.toString(), options = mapOf("threads" to "4")),
            translation = EngineSelection("marian-local", "ct2-quickmt-en-ar", quickmt.toString(), options = mapOf("profile" to "0", "sources" to "en", "targets" to "ar") + pins),
            voice = EngineSelection("supertonic-local", "supertonic-3", supertonic.toString(), options = mapOf("steps" to "5")),
            sourceLanguage = "en", targetLanguage = "ar", voiceId = "F1")
    }

    /**
     * Writes that setup into an empty data folder, as the app's settings file, so the packaged
     * `Hearth --serve` can be proven against real models without touching the owner's profile.
     * Set HEARTH_SERVICE_PROFILE to the folder, plus the three model paths.
     */
    @Test fun savesTheSetupIntoAScratchProfileForAPackagedProof() {
        val profile = System.getenv("HEARTH_SERVICE_PROFILE")
        assumeTrue("Set HEARTH_SERVICE_PROFILE and the model paths", !profile.isNullOrBlank())
        val directory = Path.of(profile).also { Files.createDirectories(it) }
        val saved = savedSetup()
        runBlocking {
            val store = DesktopSettingsStore(directory)
            try { store.save(saved) } finally { store.closeAndJoin() }
            assertEquals(saved, DesktopServiceHost.readSavedConfiguration(directory))
        }
    }

    @Test fun realModelsRunThroughTheServiceAndTheirMemoryIsReturned() {
        assumeTrue("Set HEARTH_SERVICE_SMOKE=1 and the model paths", System.getenv("HEARTH_SERVICE_SMOKE") == "1")
        val wave = path("HEARTH_SMOKE_WAV")
        val saved = savedSetup()
        val directory = Files.createTempDirectory("hearth-svc-real-")
        val launcher = WorkerLauncher { socket ->
            ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                "com.sal7one.hearth.desktop.service.DesktopServiceRealSmokeTestKt", socket.toString(), directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
        val started = System.nanoTime()
        val host = DesktopServiceHost(directory, launcher, savedConfiguration = { saved }, idleUnloadSeconds = 3_600)
        try {
            host.start()
            println("SERVICE_SMOKE hostStartMs=${(System.nanoTime() - started) / 1_000_000}")
            val token = Files.readString(DesktopServiceHost.approveClient(directory, "localhut"))
            val client = HttpClient.newHttpClient()
            fun call(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${host.port}$path")).header("Authorization", "Bearer $token")
                    .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
                headers.forEach(request::header)
                return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            fun json(response: HttpResponse<ByteArray>): JsonObject = Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject
            fun finish(key: String): JsonObject {
                repeat(2_400) {
                    val record = json(call("GET", "/v1/jobs/$key"))
                    if (record.getValue("state").jsonPrimitive.content in setOf("completed", "failed", "cancelled")) return record
                    Thread.sleep(50)
                }
                error("Job $key did not finish")
            }
            fun text(key: String, name: String) = call("GET", "/v1/jobs/$key/outputs/$name").body().toString(Charsets.UTF_8)

            val capabilities = json(call("GET", "/v1/capabilities"))
            assertEquals(listOf("transcribe", "translate", "speak", "pipeline"), capabilities.getValue("routes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })

            val audio = Files.readAllBytes(wave)
            val transcribe = "a".repeat(31) + "1"
            assertEquals(201, call("PUT", "/v1/jobs/$transcribe", """{"route":"transcribe","deadlineSeconds":600}""".toByteArray()).statusCode())
            assertEquals(200, call("PUT", "/v1/jobs/$transcribe/input", audio, mapOf("Content-Type" to "audio/wav", "X-Content-SHA256" to sha256Hex(audio))).statusCode())
            val heard = finish(transcribe)
            assertEquals("completed", heard.getValue("state").jsonPrimitive.content, heard.toString())
            val transcript = text(transcribe, "transcript.json")
            println("SERVICE_SMOKE transcribe audioSeconds=${heard.getValue("inputAudioSeconds").jsonPrimitive.double} record=$heard")
            println("SERVICE_SMOKE transcript=$transcript")
            assertContains(transcript.lowercase(), "museum")
            assertContains(text(transcribe, "transcript.vtt"), "WEBVTT")

            val translate = "a".repeat(31) + "2"
            call("PUT", "/v1/jobs/$translate", """{"route":"translate","text":"The museum opens at nine in the morning."}""".toByteArray())
            val translated = finish(translate)
            assertEquals("completed", translated.getValue("state").jsonPrimitive.content, translated.toString())
            val arabic = text(translate, "translation.txt")
            println("SERVICE_SMOKE translation=$arabic")
            assertContains(arabic, "المتحف")

            val speak = "a".repeat(31) + "3"
            call("PUT", "/v1/jobs/$speak", """{"route":"speak","text":"مرحبا بكم في هيرث."}""".toByteArray())
            val spoken = finish(speak)
            assertEquals("completed", spoken.getValue("state").jsonPrimitive.content, spoken.toString())
            val speech = call("GET", "/v1/jobs/$speak/outputs/speech.wav").body()
            println("SERVICE_SMOKE speak summary=${spoken.getValue("summary")} wavBytes=${speech.size}")
            assertTrue(spoken.getValue("summary").jsonObject.getValue("audioSeconds").jsonPrimitive.double > 0.5)
            assertTrue(speech.size > 44 && speech.drop(44).count { it.toInt() != 0 } > speech.size / 2, "The voice returned mostly silence")

            val runtime = json(call("GET", "/v1/runtime"))
            println("SERVICE_SMOKE runtime=$runtime")
            assertTrue(runtime.getValue("resident").jsonArray.isNotEmpty())
            val releaseStarted = System.nanoTime()
            val released = json(call("POST", "/v1/runtime/release"))
            println("SERVICE_SMOKE release=$released ms=${(System.nanoTime() - releaseStarted) / 1_000_000}")
            assertEquals(JsonNull, json(call("GET", "/v1/runtime")).getValue("worker"))
            for (key in listOf(transcribe, translate, speak)) assertEquals(200, call("DELETE", "/v1/jobs/$key").statusCode())
            assertEquals(0L, Files.list(directory.resolve("service/jobs")).use { it.count() }, "Forgotten jobs leave nothing on disk")
        } finally {
            host.close(); deleteTree(directory)
        }
    }
}
