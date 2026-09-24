package com.sal7one.common_jni.speech

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpeechFoundationTest {
    private fun packageDir(): File {
        val root = Files.createTempDirectory("speech-package").toFile()
        File(root, "model.gguf").writeBytes("GGUF-fixture".toByteArray())
        manifest(root)
        return root
    }
    private fun manifest(root: File): JSONObject {
        val model = File(root, "model.gguf")
        val sha = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        return JSONObject().put("schemaVersion", 1).put("profile", "nemotron-3.5-asr-0.6b")
            .put("roles", JSONObject().put("model", "model.gguf"))
            .put("files", JSONArray().put(JSONObject().put("path", "model.gguf").put("bytes", model.length()).put("sha256", sha)))
            .also { File(root, SpeechModelPackage.MANIFEST).writeText(it.toString()) }
    }
    private inline fun withPackage(body: (File) -> Unit) {
        val root = packageDir()
        try { body(root) } finally { root.deleteRecursively() }
    }
    private fun rejects(part: String, block: () -> Unit) {
        try { block(); fail("Expected rejection: $part") } catch (e: Exception) { assertTrue(e.toString(), e.toString().contains(part)) }
    }

    @Test fun verifiedPackageResolvesModelAndHash() = withPackage { root ->
        val pkg = SpeechModelPackage.verify(root)
        assertEquals(SpeechProfile.NEMOTRON_3_5_ASR_0_6B, pkg.profile)
        assertEquals(File(root, "model.gguf").absolutePath, pkg.path("model"))
        assertEquals(64, pkg.manifestSha256.length)
        assertEquals(12, pkg.sizeBytes)
    }
    @Test fun corruptionIsNotAcceptedAsAWorkingModel() = withPackage { root ->
        File(root, "model.gguf").writeText("GGUF-altered")
        rejects("SHA-256") { SpeechModelPackage.verify(root) }
    }
    @Test fun rejectsUndeclaredSidecars() = withPackage { root ->
        File(root, "encoder.data").writeText("unverified")
        rejects("undeclared") { SpeechModelPackage.verify(root) }
    }
    @Test fun rejectsTraversalEvenWhenDigestIsCorrect() = withPackage { root ->
        val j = manifest(root)
        j.getJSONArray("files").getJSONObject(0).put("path", "../model.gguf")
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        rejects("relative") { SpeechModelPackage.verify(root) }
    }
    @Test fun rejectsMissingAndDuplicateAssets() = withPackage { root ->
        val j = manifest(root)
        j.getJSONArray("files").put(j.getJSONArray("files").getJSONObject(0))
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        rejects("Duplicate") { SpeechModelPackage.verify(root) }
        manifest(root); File(root, "model.gguf").delete()
        rejects("missing") { SpeechModelPackage.verify(root) }
    }
    @Test fun rejectsSymbolicLinks() = withPackage { root ->
        Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "model.gguf").toPath())
        rejects("Symbolic") { SpeechModelPackage.verify(root) }
    }
    @Test fun moonshineRequiresItsThreeDeclaredRolesAndRejectsOtherLanguages() = withPackage { root ->
        val j = manifest(root).put("profile", "moonshine-tiny-en-v2")
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        rejects("roles") { SpeechModelPackage.verify(root) }
        j.put("roles", JSONObject().put("model", "model.gguf").put("encoder", "model.gguf").put("decoder", "model.gguf"))
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        assertEquals(SpeechProfile.MOONSHINE_TINY_EN, SpeechModelPackage.verify(root).profile)
        SpeechOptions(sourceLanguage = "en").validate(SpeechProfile.MOONSHINE_TINY_EN)
        rejects("source-language") { SpeechOptions(sourceLanguage = "ru").validate(SpeechProfile.MOONSHINE_TINY_EN) }
    }
    @Test fun qwenRequiresEveryTokenizerAsset() = withPackage { root ->
        val j = manifest(root).put("profile", "qwen3-asr-0.6b")
            .put("roles", JSONObject().put("frontend", "model.gguf").put("encoder", "model.gguf").put("decoder", "model.gguf").put("tokenizer", "tokenizer"))
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        rejects("tokenizer") { SpeechModelPackage.verify(root) }
    }
    @Test fun omnilingualPackageAndDeclaredSourceAreDistinctFromLanguageForcing() = withPackage { root ->
        File(root, "model.int8.onnx").writeBytes("onnx-fixture".toByteArray())
        File(root, "tokens.txt").writeText("a 1\n")
        val entries = JSONArray()
        listOf("model.int8.onnx", "tokens.txt").forEach { name ->
            val file = File(root, name)
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            entries.put(JSONObject().put("path", name).put("bytes", file.length()).put("sha256", sha))
        }
        File(root, "model.gguf").delete()
        val manifest = JSONObject().put("schemaVersion", 1).put("profile", "omnilingual-ctc-300m-v2-int8")
            .put("roles", JSONObject().put("model", "model.int8.onnx").put("tokenizer", "tokens.txt"))
            .put("files", entries)
        File(root, SpeechModelPackage.MANIFEST).writeText(manifest.toString())
        assertEquals(SpeechProfile.OMNILINGUAL_CTC_300M_V2, SpeechModelPackage.verify(root).profile)
        val capabilities = SpeechProfile.OMNILINGUAL_CTC_300M_V2.capabilities
        assertTrue(capabilities.sourceLanguageHints.isEmpty())
        assertTrue(capabilities.languages.all { !it.canForce })
        SpeechOptions(sourceLanguage = "ar").validate(SpeechProfile.OMNILINGUAL_CTC_300M_V2)
        rejects("source-language") { SpeechOptions(sourceLanguage = "he").validate(SpeechProfile.OMNILINGUAL_CTC_300M_V2) }
        File(root, "tokens.txt").delete()
        rejects("missing") { SpeechModelPackage.verify(root) }
    }
    @Test fun rejectsFractionalSchemaAndAssetSizes() = withPackage { root ->
        val j = manifest(root).put("schemaVersion", 1.5)
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        rejects("schema") { SpeechModelPackage.verify(root) }
        j.put("schemaVersion", 1).getJSONArray("files").getJSONObject(0).put("bytes", 12.5)
        File(root, SpeechModelPackage.MANIFEST).writeText(j.toString())
        rejects("integer") { SpeechModelPackage.verify(root) }
    }
    @Test fun recognizersNeverAdvertiseTranslation() {
        SpeechProfile.entries.forEach { assertTrue(it.capabilities.translationTargets.isEmpty()) }
        assertFalse(SpeechProfile.QWEN3_ASR_0_6B.capabilities.partialResults)
        assertTrue(SpeechProfile.NEMOTRON_3_5_ASR_0_6B.capabilities.partialResults)
    }
    @Test fun validatesBackendSpecificOptions() {
        SpeechOptions().validate(SpeechProfile.QWEN3_ASR_0_6B)
        SpeechProfile.QWEN3_ASR_0_6B.capabilities.languages.forEach {
            assertTrue(it.canForce)
            SpeechOptions(sourceLanguage = it.code).validate(SpeechProfile.QWEN3_ASR_0_6B)
        }
        rejects("source-language") { SpeechOptions(sourceLanguage = "ur").validate(SpeechProfile.QWEN3_ASR_0_6B) }
        rejects("thread") { SpeechOptions(numThreads = 8).validate(SpeechProfile.NEMOTRON_3_5_ASR_0_6B) }
        rejects("rightContext") { SpeechOptions(rightContext = 2).validate(SpeechProfile.NEMOTRON_3_5_ASR_0_6B) }
        rejects("20ms") { SpeechOptions(maxUtteranceMs = 1001).validate(SpeechProfile.QWEN3_ASR_0_6B) }
        rejects("source-language") { SpeechOptions(sourceLanguage = "he").validate(SpeechProfile.NEMOTRON_3_5_ASR_0_6B) }
        assertEquals("ru-RU", SpeechLanguage.nemoLocale("ru"))
        assertEquals("zh-CN", SpeechLanguage.nemoLocale("Chinese"))
        assertEquals("ar-AR", SpeechLanguage.nemoLocale("ar"))
    }

    @Test fun startupStagesPreserveOwnershipWhenObserverFailsAfterAllocation() = withPackage { root ->
        val driver = FakeDriver()
        val stages = mutableListOf<String>()
        runBlocking {
            try {
                SpeechRuntime { driver }.open(root, onStage = { stage ->
                    stages += stage
                    if (stage == "native recognizer created") error("diagnostic storage failed")
                })
                fail("Expected observer failure")
            } catch (e: IllegalStateException) { assertEquals("diagnostic storage failed", e.message) }
        }
        assertEquals(listOf("verifying package files", "creating native recognizer", "native recognizer created"), stages)
        assertEquals(1, driver.destroyed.get())
    }

    @Test fun invalidPackageReportsVerificationBeforeAnyNativeAllocation() = withPackage { root ->
        File(root, "model.gguf").writeText("bad")
        val driver = FakeDriver()
        val stages = mutableListOf<String>()
        runBlocking {
            try { SpeechRuntime { driver }.open(root, onStage = { stages += it }); fail("Expected verification error") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("wrong size")) }
        }
        assertEquals(listOf("verifying package files"), stages)
        assertNull(driver.config)
        assertEquals(0, driver.destroyed.get())
    }

    private class FakeDriver : SpeechDriver {
        val destroyed = AtomicInteger()
        var onCreate: (() -> Unit)? = null
        var onPush: (() -> Unit)? = null
        var failPush: Throwable? = null
        var offset = 0L
        var id = 0
        var config: JSONObject? = null
        val audio = mutableListOf<Short>()
        override fun probe(backend: String) = """{"available":false,"revision":"","error":"dlopen 原因"}"""
        override fun create(config: String): Long { this.config = JSONObject(config); onCreate?.invoke(); return 1 }
        override fun push(handle: Long, buffer: ByteBuffer, byteOffset: Int, byteCount: Int, sampleOffset: Long): String {
            onPush?.invoke(); failPush?.let { throw it }
            assertEquals(offset, sampleOffset)
            val copy = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in byteOffset until byteOffset + byteCount step 2) audio.add(copy.getShort(i))
            offset += byteCount / 2
            return update("same sentence")
        }
        fun update(text: String) = JSONObject().put("acceptedSamples", offset).put("events", JSONArray().put(JSONObject()
            .put("text", text).put("language", "ru-RU").put("utteranceId", ++id).put("revision", 1)
            .put("final", true).put("audioEndSamples", offset))).toString()
        override fun finish(handle: Long) = update("tail")
        override fun reset(handle: Long) { offset = 0 }
        override fun destroy(handle: Long) { destroyed.incrementAndGet() }
    }
    @Test fun missingRuntimePreservesLoaderReason() {
        val availability = SpeechRuntime { FakeDriver() }.availability(SpeechBackend.NEMOTRON_3_5)
        assertFalse(availability.available); assertEquals("dlopen 原因", availability.error)
    }
    @Test fun pcmSilenceSignedSamplesAndRepeatedFinalsSurvive() = runBlocking {
        val d = FakeDriver(); val s = SpeechSession(d, 1, SpeechProfile.NEMOTRON_3_5_ASR_0_6B)
        try {
            val a = s.accept(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE), 0)
            val b = s.accept(shortArrayOf(0, 0), 3)
            assertEquals(listOf(Short.MIN_VALUE, 0.toShort(), Short.MAX_VALUE, 0.toShort(), 0.toShort()), d.audio)
            assertEquals(a.transcripts[0].text, b.transcripts[0].text)
            assertNotEquals(a.transcripts[0].utteranceId, b.transcripts[0].utteranceId)
            assertEquals("ru", a.transcripts[0].sourceLanguage)
            assertEquals("tail", s.finish().transcripts.single().text)
        } finally { s.close(); s.close() }
        assertEquals(1, d.destroyed.get())
    }
    @Test fun discontinuityRequiresExplicitReset() = runBlocking {
        val d = FakeDriver(); val s = SpeechSession(d, 1, SpeechProfile.NEMOTRON_3_5_ASR_0_6B)
        try {
            s.accept(shortArrayOf(1), 0)
            try { s.accept(shortArrayOf(1), 2); fail() } catch (_: IllegalArgumentException) {}
            s.reset()
            assertEquals(1, s.accept(shortArrayOf(1), 0).acceptedSamples)
        } finally { s.close() }
    }
    @Test fun nativeFailureRemainsVerbatimAndDoesNotTurnIntoSuccess() = runBlocking {
        val d = FakeDriver(); val error = IllegalStateException("Ort graph 原因 🧪"); d.failPush = error
        val s = SpeechSession(d, 1, SpeechProfile.QWEN3_ASR_0_6B)
        try {
            try { s.accept(shortArrayOf(1), 0); fail() } catch (e: Exception) { assertEquals(error.message, e.message) }
            try { s.finish(); fail() } catch (e: Exception) { assertEquals(error.message, e.message) }
        } finally { s.close() }
    }
    @Test fun cancelledLoadReleasesAllocatedHandle() = runBlocking {
        val root = packageDir(); val d = FakeDriver()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        d.onCreate = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val loading = launch(Dispatchers.Default) { SpeechRuntime { d }.open(root) }
            assertTrue(started.await(5, TimeUnit.SECONDS)); loading.cancel(); release.countDown(); loading.join()
            assertEquals(1, d.destroyed.get())
        } finally { release.countDown(); root.deleteRecursively() }
    }
    @Test fun boundedWorkerFailsLoudlyAndSuppressesLateResults() = runBlocking {
        val d = FakeDriver(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        d.onPush = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val p = LiveSpeechProcessor(SpeechSession(d, 1, SpeechProfile.NEMOTRON_3_5_ASR_0_6B), 320)
        try {
            assertTrue(p.tryAccept(ShortArray(320)))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(p.tryAccept(shortArrayOf(1)))
            val state = p.state.value as SpeechProcessorState.Failed
            assertTrue(state.error.message!!.contains("cannot keep up"))
            release.countDown()
            withTimeout(5000) { while (d.destroyed.get() == 0) delay(10) }
            assertTrue(p.snapshot().finals.isEmpty())
        } finally { release.countDown(); p.close() }
        assertEquals(1, d.destroyed.get())
    }
    @Test fun clearDiscardsInFlightAndQueuedAudioWithoutReloadingRecognizer() = runBlocking {
        val d = FakeDriver(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        d.onPush = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val p = LiveSpeechProcessor(SpeechSession(d, 1, SpeechProfile.NEMOTRON_3_5_ASR_0_6B))
        try {
            assertTrue(p.tryAccept(shortArrayOf(1)))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(p.tryAccept(shortArrayOf(2)))
            val resetting = async(start = CoroutineStart.UNDISPATCHED) { p.reset() }
            assertFalse(p.tryAccept(shortArrayOf(3)))
            release.countDown(); withTimeout(5000) { resetting.await() }
            assertEquals(0, d.destroyed.get())
            assertTrue(p.snapshot().finals.isEmpty())
            assertEquals(listOf<Short>(1), d.audio)
            assertTrue(p.tryAccept(shortArrayOf(4)))
            withTimeout(5000) { while (p.snapshot().processedSamples != 1L) delay(10) }
            assertEquals(listOf<Short>(1, 4), d.audio)
            assertEquals(SpeechProcessorState.Running, p.state.value)
            p.reset(); assertEquals(0L, p.snapshot().submittedSamples)
        } finally { release.countDown(); p.close() }
        assertEquals(1, d.destroyed.get())
    }

    @Test fun stopDuringResetCannotLeakOrHangTheResetCaller() = runBlocking {
        val d = FakeDriver(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        d.onPush = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val p = LiveSpeechProcessor(SpeechSession(d, 1, SpeechProfile.QWEN3_ASR_0_6B))
        try {
            assertTrue(p.tryAccept(shortArrayOf(1)))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val resetting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { p.reset() } }
            val closing = launch(start = CoroutineStart.UNDISPATCHED) { p.close() }
            release.countDown()
            withTimeout(5000) { closing.join(); assertTrue(resetting.await().isFailure) }
            assertEquals(1, d.destroyed.get())
        } finally { release.countDown(); p.close() }
    }

    @Test fun immediateStopAlwaysReleasesLoadedModel() = runBlocking {
        repeat(30) {
            val d = FakeDriver()
            val p = LiveSpeechProcessor(SpeechSession(d, 1, SpeechProfile.QWEN3_ASR_0_6B))
            p.close(); p.close()
            assertEquals(1, d.destroyed.get())
            assertTrue(p.snapshot().finals.isEmpty())
        }
    }
    @Test fun invalidFirstFrameReleasesWithoutWaitingForOwnerStop() = runBlocking {
        val d = FakeDriver()
        val p = LiveSpeechProcessor(SpeechSession(d, 1, SpeechProfile.NEMOTRON_3_5_ASR_0_6B))
        try {
            assertFalse(p.tryAccept(shortArrayOf()))
            assertTrue(p.state.value is SpeechProcessorState.Failed)
            withTimeout(5000) { while (d.destroyed.get() == 0) delay(10) }
            assertEquals(1, d.destroyed.get())
        } finally { p.close() }
    }
    @Test fun workerDrainsFinalsExactlyOnceAndFlushesTail() = runBlocking {
        val d = FakeDriver(); val p = LiveSpeechProcessor(SpeechSession(d, 1, SpeechProfile.NEMOTRON_3_5_ASR_0_6B))
        try {
            assertTrue(p.tryAccept(shortArrayOf(1, 2))); assertTrue(p.tryAccept(shortArrayOf(0, 0)))
            p.finish(); assertFalse(p.tryAccept(shortArrayOf(1)))
            withTimeout(5000) { while (p.state.value == SpeechProcessorState.Running) delay(10) }
            assertEquals(SpeechProcessorState.Finished, p.state.value)
            assertEquals(listOf("same sentence", "same sentence", "tail"), p.snapshot().finals.map { it.text })
            assertTrue(p.snapshot().finals.isEmpty())
            assertNotNull(p.snapshot().realTimeFactor)
        } finally { p.close() }
    }
}
