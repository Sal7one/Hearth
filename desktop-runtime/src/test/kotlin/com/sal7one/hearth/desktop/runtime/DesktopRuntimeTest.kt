package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.TargetDataLine
import kotlin.test.*

class DesktopRuntimeTest {
    @Test fun windowsDependencyPreflightRejectsTamperedOrMissingFilesBeforeLoading() {
        val directory = Files.createTempDirectory("hearth-windows-crt-fixture")
        try {
            val files = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll", "onnxruntime.dll")
            val hashes = files.associateWith { name ->
                val bytes = "fixture-$name".toByteArray()
                Files.write(directory.resolve(name), bytes)
                sha256(bytes)
            }
            val ordered = nativeDependencyOrder("hearth_supertonic_jni", "windows-x64", hashes.keys)
            assertEquals(files.map(directory::resolve), verifiedNativeDependencies(directory, ordered, hashes))
            Files.writeString(directory.resolve("msvcp140_1.dll"), "modified dependency")
            val changed = assertFailsWith<IllegalStateException> { verifiedNativeDependencies(directory, ordered, hashes) }
            assertContains(changed.message.orEmpty(), "checksum failed: msvcp140_1.dll")
            Files.delete(directory.resolve("msvcp140_1.dll"))
            val missing = assertFailsWith<IllegalStateException> { verifiedNativeDependencies(directory, ordered, hashes) }
            assertContains(missing.message.orEmpty(), "not a regular file: msvcp140_1.dll")
            val payload = "fixture-Windows-CRT".toByteArray()
            var resource = ""
            assertContentEquals(payload, readPackagedNative("windows-x64", "vcruntime140.dll", sha256(payload)) { resource = it; payload })
            assertEquals("/native/windows-x64/vcruntime140.dll.payload", resource)
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun windowsOnnxDependenciesAreCompleteOrderedAndStageSpecific() {
        val dependencies = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll", "onnxruntime.dll")
        val files = dependencies.toSet()
        assertEquals(dependencies, nativeDependencyOrder("hearth_supertonic_jni", "windows-x64", files))
        for (missing in dependencies.take(4)) {
            val error = assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_supertonic_jni", "windows-x64", files - missing) }
            assertContains(error.message.orEmpty(), missing)
            assertContains(error.message.orEmpty(), "Microsoft Visual C++")
        }
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_supertonic_jni", "windows-x64", files - "onnxruntime.dll") }
        assertEquals(emptyList(), nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", emptySet()))
        assertEquals(emptyList(), nativeDependencyOrder("transiber_translation", "windows-x64", emptySet()))
        assertEquals(listOf("libonnxruntime.1.20.1.dylib"), nativeDependencyOrder("hearth_supertonic_jni", "macos-arm64", setOf("libonnxruntime.1.20.1.dylib")))
        assertEquals(listOf("libonnxruntime.dylib"), nativeDependencyOrder("hearth_supertonic_jni", "macos-x64", setOf("libonnxruntime.dylib")))
        assertEquals(listOf("libonnxruntime.1.20.1.dylib"), nativeDependencyOrder("hearth_desktop_qwen3_asr", "macos-arm64", setOf("libonnxruntime.1.20.1.dylib")))
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_desktop_qwen3_asr", "macos-arm64", emptySet()) }
        assertEquals(listOf("libvosk.dylib"), nativeDependencyOrder("hearth_vosk", "macos-arm64", setOf("libvosk.dylib")))
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_vosk", "macos-arm64", setOf("libhearth_vosk.dylib")) }
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_vosk", "windows-x64", setOf("libvosk.dylib")) }
        assertEquals(emptyList(), nativeDependencyOrder("hearth_desktop_qwen3_asr", "windows-x64", emptySet()))
    }
    @Test fun malformedPastedCredentialsNeverReachHttpHeadersOrErrors() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("hearth-secret-header-test")
        try {
            for (suffix in listOf("\u200b", "\n", "\r", "\t", " ", "\u0080")) {
                val key = "fixture-usable-secret" + suffix
                val cloud = DesktopCloud(CredentialResolver { key }, folder)
                val failure = assertFailsWith<IllegalArgumentException> {
                    cloud.request(EngineSelection("openai-translation", credentialId = "fixture", endpoint = "http://127.0.0.1:1/v1"), "", "application/json", byteArrayOf(), 1000)
                }
                assertEquals("Provider credential is missing or malformed", failure.message)
                assertFalse(failure.toString().contains("fixture-usable-secret"))
                assertNull(failure.cause)
            }
        } finally { folder.toFile().deleteRecursively() }
    }
    @Test fun providerKeyIsRedactedBeforeTheErrorLengthLimit() {
        val key = "sk-example-secret-token"
        val message = "x".repeat(2040) + key + " was rejected"
        val output = redactedProviderError(message, key)
        assertEquals(2048, output.length)
        assertFalse(output.contains("sk-examp"), "Truncating first would leak a provider key prefix")
        assertTrue(output.endsWith("[redacte"))
    }
    @Test fun nativeManifestRejectsCrossPlatformPathEscapesAndInvalidHashes() {
        fun manifest(filename: String, hash: String) = kotlinx.serialization.json.buildJsonObject {
            put("files", kotlinx.serialization.json.buildJsonObject { put(filename, kotlinx.serialization.json.JsonPrimitive(hash)) })
        }
        val hash = "a".repeat(64)
        for (filename in listOf(".", "..", "../escape.dll", "C:escape.dll", "C:\\escape.dll", "/escape.dylib", "bad/name.so", "a".repeat(181))) {
            assertFailsWith<IllegalArgumentException>(filename) { nativeBundleFiles(manifest(filename, hash)) }
        }
        for (invalid in listOf("A".repeat(64), "a".repeat(63), "g".repeat(64), "")) {
            assertFailsWith<IllegalArgumentException>(invalid) { nativeBundleFiles(manifest("runtime.dll", invalid)) }
        }
        assertEquals(mapOf("libhearth_desktop_whisper.dylib" to hash), nativeBundleFiles(manifest("libhearth_desktop_whisper.dylib", hash)))
    }
    @Test fun cancellationJoinsItsBlockingOwnerBeforeReturning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = AtomicBoolean()
        val operation = launch(Dispatchers.Default) {
            joinedBlocking({ release.countDown() }) {
                entered.complete(Unit)
                check(release.await(3, TimeUnit.SECONDS))
                Thread.sleep(60)
                finished.set(true)
            }
        }
        withTimeout(3000) { entered.await(); operation.cancelAndJoin() }
        assertTrue(finished.get(), "Cancelled native work must be joined")
    }

    @Test fun microphoneCancellationJoinsTheCaptureProducer() = runBlocking {
        val blockedRead = CountDownLatch(1); val release = CountDownLatch(1)
        val readReturned = AtomicBoolean(); val count = AtomicInteger()
        val source = fakeLine(onRead = { buffer ->
            if (count.incrementAndGet() == 1) { buffer.fill(0); buffer.size }
            else { blockedRead.countDown(); check(release.await(3, TimeUnit.SECONDS)); Thread.sleep(60); readReturned.set(true); 0 }
        }, onClose = { release.countDown() })
        val capture = DesktopAudioCapture { source }
        val received = CompletableDeferred<Unit>()
        val collection = launch(Dispatchers.Default) { capture.start().collect { received.complete(Unit); awaitCancellation() } }
        withTimeout(3000) { received.await() }
        assertTrue(blockedRead.await(3, TimeUnit.SECONDS))
        withTimeout(3000) { collection.cancelAndJoin() }
        assertTrue(readReturned.get(), "Capture cannot return while its producer is still inside read()")
        capture.cancel()
    }

    @Test fun microphoneQueueOverflowIsAVisibleBoundedFailure() = runBlocking {
        val closed = CountDownLatch(1)
        val reads = AtomicInteger()
        val source = fakeLine(onRead = { buffer -> reads.incrementAndGet(); buffer.fill(0); buffer.size }, onClose = { closed.countDown() })
        val capture = DesktopAudioCapture { source }
        val release = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> { capture.start().collect { release.await() } }
        }
        assertTrue(withContext(Dispatchers.IO) { closed.await(3, TimeUnit.SECONDS) })
        release.complete(Unit)
        val failure = withTimeout(3000) { result.await() }
        assertContains(failure.message.orEmpty(), "queue overflow")
        assertTrue(reads.get() <= 19, "Microphone must bound 16 queued frames plus producer/emitter/consumer; actual reads=${reads.get()}")
        assertEquals(0L, closed.count)
    }

    @Test fun wavOutputConvertsToBoundedSpeechFramesAndRejectsUnsupportedFormats() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("hearth-wav-test")
        try {
            val path = folder.resolve("article.wav")
            WaveWriter(path, 44100).use { writer ->
                writer.append(FloatArray(44100) { .5f * kotlin.math.sin(2 * kotlin.math.PI * 10_000 * it / 44_100).toFloat() })
                writer.publish()
            }
            val frames = DesktopAudioFiles.frames(path.toString()).toList()
            assertTrue(DesktopAudioFiles.supports(path))
            assertTrue(frames.all { it.bytes.size <= 3200 && it.bytes.size % 2 == 0 })
            assertEquals(32_000, frames.sumOf { it.bytes.size })
            assertTrue(frames.drop(1).dropLast(1).all { frame ->
                val samples = java.nio.ByteBuffer.wrap(frame.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                (0 until samples.limit()).all { kotlin.math.abs(samples.get(it).toInt()) <= 3 }
            })
            val unsupported = folder.resolve("clip.mp4"); Files.write(unsupported, "not a WAV".toByteArray())
            assertFalse(DesktopAudioFiles.supports(unsupported))
            val mislabeled = folder.resolve("pcm.mp4"); Files.copy(path, mislabeled)
            assertTrue(DesktopAudioFiles.supports(mislabeled))
            assertFailsWith<IllegalArgumentException> { DesktopAudioFiles.frames(unsupported.toString()).toList() }

            val stereo = folder.resolve("stereo.wav")
            val bytes = java.nio.ByteBuffer.allocate(44 + 4800 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(48000).putInt(48000 * 4).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(4800 * 4)
            repeat(4800) { bytes.putShort(2000).putShort(-2000) }
            Files.write(stereo, bytes.array())
            assertTrue(DesktopAudioFiles.supports(stereo))
            assertTrue(DesktopAudioFiles.frames(stereo.toString()).toList().single().bytes.contentEquals(ByteArray(3200)))
        } finally { folder.toFile().deleteRecursively() }
    }

    @Test fun unpublishedAudioIsDeletedOnFailure() {
        val folder = Files.createTempDirectory("hearth-audio-failure")
        try {
            val path = folder.resolve("article.wav")
            WaveWriter(path, 16000).use { assertFailsWith<IllegalArgumentException> { it.append(floatArrayOf(Float.NaN)) } }
            assertFalse(Files.exists(path)); assertFalse(Files.exists(folder.resolve("article.wav.part")))
        } finally { folder.toFile().deleteRecursively() }
    }

    @Test fun cloudEndpointRejectsQuerySecretsCredentialsFragmentsAndRemotePlaintext() {
        for (endpoint in listOf("https://api.example.test/v1?api_key=secret", "https://user:secret@example.test/v1", "https://example.test/v1#secret", "http://example.test/v1")) {
            assertFailsWith<IllegalArgumentException>(endpoint) { DesktopCloud.providerEndpoint(endpoint) }
        }
        assertEquals("localhost", DesktopCloud.providerEndpoint("http://localhost:9000/v1").host)
    }

    @Test fun openAiBasesResolvePerStageAndExplicitEndpointsRemainUnchanged() {
        val operations = mapOf("openai-translation" to "chat/completions", "openai-stt" to "audio/transcriptions", "openai-tts" to "audio/speech")
        for ((engine, operation) in operations) {
            for (base in listOf("https://example.test", "https://example.test/", "https://example.test/v1", "https://example.test/v1/")) {
                assertEquals("https://example.test/v1/$operation", DesktopCloud.providerRequestEndpoint(base, engine).toString())
            }
            assertEquals("https://example.test/proxy/v1/$operation", DesktopCloud.providerRequestEndpoint("https://example.test/proxy/v1/", engine).toString())
            for (explicit in listOf("https://example.test/translation", "https://example.test/v1/$operation", "https://example.test/custom%20path/translation/")) {
                assertEquals(explicit, DesktopCloud.providerRequestEndpoint(explicit, engine).toString())
            }
        }
        assertEquals("http://localhost:8787/speech", DesktopCloud.providerRequestEndpoint("http://localhost:8787/speech", "ming-tts").toString())
        assertFailsWith<IllegalArgumentException> { DesktopCloud.providerRequestEndpoint("http://192.168.1.2:11434/v1", "openai-translation") }
        assertFailsWith<IllegalArgumentException> { DesktopCloud.providerRequestEndpoint("https://example.test/v1?api_key=secret", "openai-translation") }
    }

    @Test fun cloudTranslationUsesOpenAiCompletionPathFromServerRootsAndVersionedBases() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        server.createContext("/") { exchange ->
            synchronized(paths) { paths += exchange.requestURI.path }
            exchange.requestBody.use { it.readBytes() }
            val response = """{"choices":[{"finish_reason":"stop","message":{"content":"مرحبا"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val folder = Files.createTempDirectory("hearth-openai-base-test")
        try {
            val cloud = DesktopCloud(CredentialResolver { null }, folder)
            for (suffix in listOf("", "/v1", "/v1/")) {
                val route = EngineSelection("openai-translation", "local-translator", endpoint = "http://127.0.0.1:${server.address.port}$suffix")
                assertEquals("مرحبا", withTimeout(5000) { cloud.translate("Hello", route, "en", "ar") })
            }
            assertEquals(List(3) { "/v1/chat/completions" }, synchronized(paths) { paths.toList() })
        } finally { server.stop(0); folder.toFile().deleteRecursively() }
    }

    @Test fun cloudTranscriptionSendsModelSpecificLanguageHints() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/v1/audio/transcriptions") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val requestNumber = synchronized(requests) { requests.add(body); requests.size }
            val response = if (requestNumber <= 2) """{"text":"مرحبا"}""" else """{"text":null}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val folder = Files.createTempDirectory("hearth-openai-stt-test")
        try {
            val cloud = DesktopCloud(CredentialResolver { null }, folder)
            val endpoint = "http://127.0.0.1:${server.address.port}/v1"
            val selection = EngineSelection("openai-stt", "", endpoint = endpoint)
            assertEquals("مرحبا", withTimeout(5000) { cloud.recognize(ByteArray(2), selection, "ar") })
            assertEquals("مرحبا", withTimeout(5000) { cloud.recognize(ByteArray(2), selection.copy(modelId = "whisper-1"), "ar") })
            assertFailsWith<IllegalStateException> {
                withTimeout(5000) { cloud.recognize(ByteArray(2), selection, "auto") }
            }
            val (current, legacy, auto) = synchronized(requests) { requests.toList() }
            assertTrue(current.contains("name=\"model\"\r\n\r\ngpt-transcribe\r\n"))
            assertTrue(current.contains("name=\"languages[]\"\r\n\r\nar\r\n"))
            assertTrue(legacy.contains("name=\"language\"\r\n\r\nar\r\n"))
            assertFalse(auto.contains("name=\"languages[]\""))
        } finally { server.stop(0); folder.toFile().deleteRecursively() }
    }

    @Test fun cloudUsesOnlySelectedEndpointAndPreservesRedactedProviderErrors() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val destinationCalls = AtomicInteger()
        val key = "provider-secret-test"
        val authorization = java.util.concurrent.atomic.AtomicReference<String>()
        val requestedText = java.util.concurrent.atomic.AtomicReference<String>()
        server.createContext("/translation") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            requestedText.set(body)
            val response = """{"error":{"message":"Denied $key"}}""".toByteArray()
            exchange.sendResponseHeaders(401, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/destination")
            exchange.sendResponseHeaders(302, 0); exchange.responseBody.close()
        }
        server.createContext("/destination") { exchange -> destinationCalls.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close() }
        server.start()
        val folder = Files.createTempDirectory("hearth-http-test")
        try {
            val cloud = DesktopCloud(CredentialResolver { key }, folder)
            val selection = EngineSelection("openai-translation", endpoint = "http://127.0.0.1:${server.address.port}/translation", credentialId = "test")
            val error = withTimeout(5000) { assertFailsWith<IllegalStateException> { cloud.translate("مرحبا", selection, "ar", "en") } }
            assertEquals("Bearer $key", authorization.get()); assertContains(requestedText.get(), "مرحبا")
            assertContains(error.message.orEmpty(), "HTTP 401"); assertContains(error.message.orEmpty(), "[redacted]")
            assertFalse(error.message.orEmpty().contains(key))
            withTimeout(5000) { assertFailsWith<IllegalStateException> { cloud.request(selection.copy(endpoint = "http://127.0.0.1:${server.address.port}/redirect"), "", "application/json", "{}".toByteArray(), 4096) } }
            assertEquals(0, destinationCalls.get(), "Provider credentials must never follow redirects")
        } finally { server.stop(0); folder.toFile().deleteRecursively() }
    }

    @Test fun translationPromptsPreservePublisherNewlinesAndDirectionRules() {
        assertEquals("You are an expert at translating text from Arabic to English.\n\nمرحبا", DesktopTranslationPrompts.prompt("مرحبا", "ar", "en", "riva-translate"))
        assertEquals("Translate the following Arabic source text to English:\nArabic: مرحبا\nEnglish:", DesktopTranslationPrompts.prompt("مرحبا", "ar", "en", "eurollm"))
        assertFailsWith<IllegalArgumentException> { DesktopTranslationPrompts.prompt("مرحبا", "ar", "fr", "riva-translate") }
    }

    private fun fakeLine(onRead: (ByteArray) -> Int, onClose: () -> Unit): TargetDataLine = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(TargetDataLine::class.java),
    ) { _, method, args ->
        when (method.name) {
            "read" -> onRead(args!![0] as ByteArray)
            "close" -> { onClose(); null }
            "isOpen", "isActive", "isRunning" -> true
            "getBufferSize", "available", "getFramePosition" -> 0
            "getLongFramePosition", "getMicrosecondPosition" -> 0L
            "getLevel" -> 0f
            else -> null
        }
    } as TargetDataLine
}
