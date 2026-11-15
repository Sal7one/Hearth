package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopServiceHostTest {
    private lateinit var directory: Path
    private lateinit var host: DesktopServiceHost
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()
    /** Seconds of audio the stand-in decoder reports for the next upload. */
    private var decodedSeconds = 2

    private val launcher = WorkerLauncher { socket ->
        ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
            "com.sal7one.hearth.desktop.service.FakeWorkerKt", socket.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
    private val saved = PipelineConfig(speech = EngineSelection("fake-stt"), translation = EngineSelection("fake-mt"), voice = EngineSelection("fake-tts"),
        sourceLanguage = "en", targetLanguage = "ar", voiceId = "F1")

    @BeforeTest fun start() {
        directory = Files.createTempDirectory("hearth-svc-")
        host = DesktopServiceHost(directory, launcher, savedConfiguration = { saved }, decode = { _, target ->
            target.resolve("decoded.wav").also { wave -> RandomAccessFile(wave.toFile(), "rw").use { it.setLength(44L + 32_000L * decodedSeconds) } }
        }, idleUnloadSeconds = 3_600)
        host.start()
        token = Files.readString(DesktopServiceHost.approveClient(directory, "localhut"))
    }

    @AfterTest fun stop() { host.close(); deleteTree(directory) }

    private fun call(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), authorized: Boolean = true): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${host.port}$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
        if (authorized) request.header("Authorization", "Bearer $token")
        headers.forEach(request::header)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun json(response: HttpResponse<ByteArray>): JsonObject = Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject
    private fun errorCode(response: HttpResponse<ByteArray>) = json(response).getValue("error").jsonObject.getValue("code").jsonPrimitive.content
    private fun key(number: Int) = number.toString(16).padStart(32, '0')
    private fun create(number: Int, body: String) = call("PUT", "/v1/jobs/${key(number)}", body.toByteArray())
    private fun await(number: Int, vararg states: String): JsonObject {
        repeat(400) {
            val record = json(call("GET", "/v1/jobs/${key(number)}"))
            if (record.getValue("state").jsonPrimitive.content in states) return record
            Thread.sleep(25)
        }
        error("Job ${key(number)} did not reach ${states.toList()}")
    }

    @Test fun healthNeedsNoTokenAndEverythingElseDoes() {
        assertEquals("""{"service":"Hearth","protocol":1,"state":"ready"}""", call("GET", "/health", authorized = false).body().toString(Charsets.UTF_8))
        assertEquals(401, call("GET", "/v1/capabilities", authorized = false).statusCode())
        assertEquals(401, call("GET", "/v1/capabilities", headers = mapOf("Authorization" to "Bearer wrong"), authorized = false).statusCode())
        assertEquals(403, call("GET", "/v1/capabilities", headers = mapOf("Origin" to "https://example.org")).statusCode(), "A web page must not reach the service")
        assertEquals(404, call("GET", "/v1/unknown").statusCode())
        assertEquals(405, call("POST", "/health", authorized = false).statusCode())
    }

    @Test fun capabilitiesListReadyRoutesFromMemoryWithoutStartingAWorker() {
        val started = System.nanoTime()
        val capabilities = json(call("GET", "/v1/capabilities"))
        assertTrue((System.nanoTime() - started) / 1e6 < 1_000, "Capabilities must answer in well under a second")
        val routes = capabilities.getValue("routes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("transcribe", "translate", "speak", "pipeline"), routes.map { it.getValue("id").jsonPrimitive.content })
        val translate = routes[1]
        assertEquals(listOf("en"), translate.getValue("sourceLanguages").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("ar"), translate.getValue("targetLanguages").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, translate.getValue("speed"), "An unmeasured route reports no speed")
        assertFalse(translate.getValue("resident").jsonPrimitive.boolean)
        assertFalse(capabilities.toString().contains("modelPath"), "No path may leave Hearth")
        assertEquals(JsonNull, json(call("GET", "/v1/runtime")).getValue("worker"), "Start-up and capabilities hold no worker")
    }

    @Test fun aTextJobIsIdempotentKeepsItsTextOutOfTheRecordAndIsForgottenCompletely() {
        val body = """{"route":"translate","text":"Hello museum"}"""
        val created = create(1, body)
        assertEquals(201, created.statusCode())
        val record = await(1, "completed")
        assertEquals(200, create(1, body).statusCode(), "The same key and body returns the existing job")
        assertEquals("key_conflict", errorCode(create(1, """{"route":"translate","text":"Different"}""")))
        assertFalse(record.toString().contains("museum"), "The record never carries text")
        assertEquals(12, record.getValue("summary").jsonObject.getValue("characters").jsonPrimitive.int)
        val output = record.getValue("outputs").jsonArray.single().jsonObject
        assertEquals("translation.txt", output.getValue("name").jsonPrimitive.content)
        val whole = call("GET", "/v1/jobs/${key(1)}/outputs/translation.txt")
        assertEquals("muesum olleH", whole.body().toString(Charsets.UTF_8))
        // The record names the bare type; the download says how the text is encoded.
        assertEquals("text/plain", output.getValue("mime").jsonPrimitive.content)
        assertEquals("text/plain; charset=utf-8", whole.headers().firstValue("Content-Type").get())
        assertEquals(sha256Hex(whole.body()), output.getValue("sha256").jsonPrimitive.content)
        val part = call("GET", "/v1/jobs/${key(1)}/outputs/translation.txt", headers = mapOf("Range" to "bytes=2-5"))
        assertEquals(206, part.statusCode()); assertEquals("esum", part.body().toString(Charsets.UTF_8))
        assertEquals("bytes 2-5/12", part.headers().firstValue("Content-Range").get())
        assertEquals("olleH", call("GET", "/v1/jobs/${key(1)}/outputs/translation.txt", headers = mapOf("Range" to "bytes=-5")).body().toString(Charsets.UTF_8))
        assertEquals(416, call("GET", "/v1/jobs/${key(1)}/outputs/translation.txt", headers = mapOf("Range" to "bytes=40-50")).statusCode())
        assertEquals(404, call("GET", "/v1/jobs/${key(1)}/outputs/..%2Finput.wav").statusCode())

        assertEquals("""{"key":"${key(1)}","state":"forgotten"}""", call("DELETE", "/v1/jobs/${key(1)}").body().toString(Charsets.UTF_8))
        assertEquals(200, call("DELETE", "/v1/jobs/${key(1)}").statusCode(), "Forget is idempotent")
        assertEquals("unknown_job", errorCode(call("GET", "/v1/jobs/${key(1)}")))
        assertEquals("key_conflict", errorCode(create(1, body)), "A forgotten key is not reused")
        assertFalse(Files.exists(directory.resolve("service/jobs/${key(1)}")), "Nothing of the job stays on disk")
        assertFalse(Files.readString(directory.resolve("logs/service.log")).contains("museum"), "The log never carries text")
    }

    @Test fun requestsAreStrictAndOnlyAskForWhatARouteLists() {
        assertEquals("invalid_request", errorCode(create(2, """{"route":"translate","text":"Hi","engine":"other"}""")))
        assertEquals("invalid_request", errorCode(create(2, """{"route":"translate","text":"Hi","targetLanguage":"fr"}""")))
        assertEquals("invalid_request", errorCode(create(2, """{"route":"translate"}""")))
        assertEquals("invalid_request", errorCode(create(2, """{"route":"translate","text":"Hi","deadlineSeconds":"soon"}""")))
        assertEquals("invalid_request", errorCode(create(2, "not json")))
        assertEquals("unknown_route", errorCode(create(2, """{"route":"missing","text":"Hi"}""")))
        assertEquals("invalid_request", errorCode(call("PUT", "/v1/jobs/NOT-A-KEY", """{"route":"translate","text":"Hi"}""".toByteArray())))
        assertEquals("not_resident", errorCode(create(2, """{"route":"translate","text":"Hi","requireResident":true}""")))
        assertEquals(201, create(2, """{"route":"translate","text":"Hi"}""").statusCode()); await(2, "completed")
        assertEquals(201, create(3, """{"route":"translate","text":"Hi","requireResident":true}""").statusCode(), "A loaded route may be required")
        await(3, "completed")
    }

    @Test fun oneJobRunsAtATimeAndForgettingAStubbornEngineEndsTheWorkerWithinBound() {
        assertEquals(201, create(4, """{"route":"translate","text":"slow"}""").statusCode()); await(4, "running")
        val busy = create(5, """{"route":"translate","text":"Hi"}""")
        assertEquals(503, busy.statusCode()); assertEquals("busy", errorCode(busy))
        assertEquals(key(4), json(call("GET", "/v1/runtime")).getValue("busy").jsonPrimitive.content, "Runtime names the active job as a field")
        assertTrue(json(busy).toString().contains(key(4)), "Busy names the active job")
        assertEquals("busy", errorCode(call("POST", "/v1/runtime/release")))
        call("DELETE", "/v1/jobs/${key(4)}")
        assertEquals(201, create(5, """{"route":"translate","text":"Hi"}""").statusCode()); await(5, "completed")
        call("DELETE", "/v1/jobs/${key(5)}")

        // An engine that ignores cancellation: the worker process is ended instead.
        assertEquals(201, create(6, """{"route":"translate","text":"hang"}""").statusCode()); await(6, "running")
        val started = System.nanoTime()
        assertEquals(200, call("DELETE", "/v1/jobs/${key(6)}").statusCode())
        assertTrue((System.nanoTime() - started) / 1e9 < 6.5, "Forget answers within its bound even when the engine does not stop")
        assertEquals(201, create(7, """{"route":"translate","text":"after"}""").statusCode())
        assertEquals("completed", await(7, "completed", "failed").getValue("state").jsonPrimitive.content, "A fresh worker serves the next job")
    }

    @Test fun engineFailuresDeadlinesAndMissingUploadsAreReportedWithTheirCodes() {
        create(8, """{"route":"translate","text":"fail"}""")
        val failed = await(8, "failed").getValue("error").jsonObject
        assertEquals("engine_failed", failed.getValue("code").jsonPrimitive.content)
        assertEquals("The fake engine was asked to fail", failed.getValue("message").jsonPrimitive.content)
        call("DELETE", "/v1/jobs/${key(8)}")
        create(14, """{"route":"speak","text":"big"}""")
        assertEquals("output_too_large", await(14, "failed").getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        call("DELETE", "/v1/jobs/${key(14)}")
        assertEquals(64L * 1024 * 1024, json(call("GET", "/v1/capabilities")).getValue("limits").jsonObject.getValue("maxOutputBytes").jsonPrimitive.long)
        create(9, """{"route":"translate","text":"slow","deadlineSeconds":1}""")
        val late = await(9, "cancelled").getValue("error").jsonObject
        assertEquals("cancelled", late.getValue("code").jsonPrimitive.content)
        assertEquals("The job passed its deadline", late.getValue("message").jsonPrimitive.content)
    }

    @Test fun anAudioJobWaitsForItsVerifiedUploadThenRunsAndMeasuresItself() {
        val audio = ByteArray(4_096) { it.toByte() }
        val digest = sha256Hex(audio)
        assertEquals("awaiting_input", json(create(10, """{"route":"transcribe"}""")).getValue("state").jsonPrimitive.content)
        fun upload(bytes: ByteArray, type: String, hash: String) = call("PUT", "/v1/jobs/${key(10)}/input", bytes, mapOf("Content-Type" to type, "X-Content-SHA256" to hash))
        assertEquals("unsupported_media", errorCode(upload(audio, "audio/ogg", digest)))
        assertEquals("invalid_request", errorCode(upload(audio, "audio/wav", "0".repeat(64))), "A wrong digest stores nothing")
        assertEquals(200, upload(audio, "audio/wav", digest).statusCode())
        assertEquals(200, upload(audio, "audio/wav", digest).statusCode(), "Repeating the same upload is a no-op")
        assertEquals("key_conflict", errorCode(upload(audio + 1, "audio/wav", sha256Hex(audio + 1))))
        val record = await(10, "completed")
        assertEquals(2.0, record.getValue("inputAudioSeconds").jsonPrimitive.double)
        assertEquals(2.0, record.getValue("summary").jsonObject.getValue("audioSeconds").jsonPrimitive.double)
        assertEquals(listOf("transcript.json", "transcript.vtt"), record.getValue("outputs").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertFalse(Files.exists(directory.resolve("service/jobs/${key(10)}/input.wav")), "The upload is deleted once the job ends")
        call("DELETE", "/v1/jobs/${key(10)}")

        // The first job measured this route, so the next one carries an estimate before it runs.
        val route = json(call("GET", "/v1/capabilities")).getValue("routes").jsonArray.first().jsonObject
        assertTrue(route.getValue("speed").jsonObject.getValue("secondsPerAudioSecond").jsonPrimitive.double > 0)
        decodedSeconds = 3_601
        create(11, """{"route":"transcribe"}""")
        call("PUT", "/v1/jobs/${key(11)}/input", audio, mapOf("Content-Type" to "audio/mpeg", "X-Content-SHA256" to digest))
        assertEquals("input_too_long", await(11, "failed").getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        call("DELETE", "/v1/jobs/${key(11)}")
        assertEquals("invalid_request", errorCode(create(12, """{"route":"transcribe","text":"typed"}""")))
        create(12, """{"route":"translate","text":"Hi"}"""); await(12, "completed")
        assertEquals("invalid_request", errorCode(call("PUT", "/v1/jobs/${key(12)}/input", audio, mapOf("Content-Type" to "audio/wav", "X-Content-SHA256" to digest))))
    }

    @Test fun releaseEndsTheWorkerAndReportsWhatItHeld() {
        create(13, """{"route":"speak","text":"Hello"}"""); val record = await(13, "completed")
        assertEquals(1.0, record.getValue("summary").jsonObject.getValue("audioSeconds").jsonPrimitive.double)
        val runtime = json(call("GET", "/v1/runtime"))
        assertEquals(listOf("speak"), runtime.getValue("resident").jsonArray.map { it.jsonPrimitive.content })
        val held = runtime.getValue("worker").jsonObject.getValue("ramBytes")
        if (held != JsonNull) assertTrue(held.jsonPrimitive.long > 8 * 1024 * 1024, "The worker's memory is measured, not guessed")
        val started = System.nanoTime()
        val released = json(call("POST", "/v1/runtime/release"))
        assertTrue((System.nanoTime() - started) / 1e9 < 10, "Release is synchronous and bounded")
        assertEquals(listOf("speak"), released.getValue("released").jsonArray.map { it.jsonPrimitive.content })
        assertNotNull(released.getValue("freed").jsonObject["ramBytes"])
        assertEquals(JsonNull, json(call("GET", "/v1/runtime")).getValue("worker"))
        assertEquals(emptyList(), json(call("POST", "/v1/runtime/release")).getValue("released").jsonArray.toList(), "Releasing nothing is not an error")
    }

    @Test fun oneHostPerDataFolderAndAStopLeavesNoDescriptorBehind() {
        assertFailsWith<ServiceAlreadyRunning> { DesktopServiceHost(directory, launcher, savedConfiguration = { saved }).start() }
        val descriptor = Json.parseToJsonElement(Files.readString(directory.resolve("service/host.json"))).jsonObject
        assertEquals(host.port, descriptor.getValue("port").jsonPrimitive.int)
        assertEquals(ProcessHandle.current().pid(), descriptor.getValue("pid").jsonPrimitive.long)
        assertTrue(PrivateFiles.ownerOnly(directory.resolve("service/clients/localhut.token")), "A token is readable by its owner only")
        assertTrue(PrivateFiles.ownerOnly(directory.resolve("service/host.json")))
        assertEquals(202, call("POST", "/v1/host/stop").statusCode())
        host.awaitStop()
        assertFalse(Files.exists(directory.resolve("service/host.json")))
        // The lock is released with the host: the folder can be served again.
        DesktopServiceHost(directory, launcher, savedConfiguration = { saved }).also { it.start() }.close()
    }

    @Test fun routesComeOnlyFromLocalEnginesThatAreReady() {
        val views = SavedRoutes.from(PipelineConfig(speech = EngineSelection("broken-stt"), translation = EngineSelection("cloud-mt"), voice = EngineSelection("fake-tts"),
            sourceLanguage = "auto", targetLanguage = "ar"), fakeEngines)
        assertEquals(listOf("transcribe", "speak"), views.map { it.route.id }, "A cloud stage offers no route, and a pipeline with one is not offered")
        assertEquals("Broken speech library failed to load", views[0].unavailable)
        assertEquals(setOf("ar"), views[1].route.sourceLanguages, "The voice speaks the translation language")
        assertEquals(emptyList(), SavedRoutes.from(PipelineConfig(voice = EngineSelection("fake-tts"), sourceLanguage = "auto"), fakeEngines).map { it.route.id },
            "A voice with no declared language is not offered")
        assertContentEquals(listOf("transcribe"), SavedRoutes.from(PipelineConfig(speech = EngineSelection("fake-stt")), fakeEngines).map { it.route.id })
    }

    @Test fun clientTokensAreExplicitPerClientAndRevocable() {
        val clients = ServiceClients(directory.resolve("service/clients"))
        assertEquals("localhut", clients.authenticate("Bearer $token"))
        assertEquals(token, Files.readString(clients.approve("localhut")), "Approving again keeps the token")
        val other = Files.readString(clients.approve("goodchat"))
        assertEquals("goodchat", clients.authenticate("Bearer $other"))
        assertEquals(null, clients.authenticate("Bearer ${token.dropLast(1)}x")); assertEquals(null, clients.authenticate(token)); assertEquals(null, clients.authenticate(null))
        assertTrue(clients.revoke("goodchat")); assertEquals(null, clients.authenticate("Bearer $other"))
        assertFailsWith<IllegalArgumentException> { clients.approve("../escape") }
    }
}
