package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.tts.*
import kotlinx.coroutines.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real ArticleReader/WAV lifecycle with injected engines; no Supertonic inference or weights are claimed. */
class DesktopVoiceCacheTest {
    @Test fun successfulCaptionsReuseOneEngineAcrossVoicesAndCanonicalPathAliases() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-cache-")
        val model = Files.createDirectory(root.resolve("models"))
        val engines = CopyOnWriteArrayList<FakeEngine>()
        val cache = DesktopVoiceCache(root) { path, _ ->
            assertEquals(model.toRealPath(), path)
            FakeEngine().also(engines::add)
        }
        try {
            val first = cache.read("hello", selection(model), "en", "F1")
            val second = cache.read("مرحبا", selection(model.resolve(".")), "ar", "F2")
            assertEquals(1, engines.size)
            assertEquals(listOf("hello:en:F1", "مرحبا:ar:F2"), engines.single().spoken)
            assertEquals(0, engines.single().closed.get())
            assertNotEquals(first.path, second.path)
            assertTrue(Files.size(Path.of(first.path)) > 44)
            assertTrue(Files.size(Path.of(second.path)) > 44)
            cache.closeAndJoin(); cache.closeAndJoin()
            assertEquals(1, engines.single().closed.get())
        } finally { cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    @Test fun changingUnusedStyleOrRootRetiresThePreviousOwnerBeforeCreatingAnother() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-stamps-")
        val model = Files.createDirectory(root.resolve("models"))
        val alternate = Files.createDirectory(root.resolve("other-models"))
        val order = CopyOnWriteArrayList<String>()
        var count = 0
        val cache = DesktopVoiceCache(root) { _, _ ->
            val index = ++count
            order += "create$index"
            FakeEngine(onClose = { order += "close$index" })
        }
        try {
            cache.read("one", selection(model), "en", "F1")
            // Missing unused styles are allowed initially, but all sixteen components affect identity.
            Files.writeString(model.resolve("M5.json"), "new unused voice")
            cache.read("two", selection(model), "en", "F1")
            Files.writeString(model.resolve("M5.json"), "changed unused voice, different byte size")
            cache.read("three", selection(model), "en", "F1")
            cache.read("four", selection(alternate), "en", "F1")
            assertEquals(listOf("create1", "close1", "create2", "close2", "create3", "close3", "create4"), order)
        } finally { cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    @Test fun failedInferenceDiscardsTheCacheAndUnpublishedWaveBeforeRetry() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-failure-")
        val model = Files.createDirectory(root.resolve("models"))
        val engines = CopyOnWriteArrayList<FakeEngine>()
        val cache = DesktopVoiceCache(root) { _, _ ->
            val fail = engines.isEmpty()
            FakeEngine(onSpeech = { _, _ -> if (fail) error("Pinned voice component failed verification") }).also(engines::add)
        }
        try {
            val error = assertFailsWith<IllegalStateException> { cache.read("failed", selection(model), "en", "F1") }
            assertEquals("Pinned voice component failed verification", error.message)
            assertEquals(1, engines.first().closed.get())
            assertEquals(0L, outputCount(root))
            cache.read("retry", selection(model), "en", "F1")
            assertEquals(2, engines.size)
            assertEquals(0, engines.last().closed.get())
        } finally { cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    @Test fun cancellationJoinsInferenceAndTeardownBeforeReplacement() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-cancel-")
        val model = Files.createDirectory(root.resolve("models"))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val exited = CompletableDeferred<Unit>()
        val engines = CopyOnWriteArrayList<FakeEngine>()
        val cache = DesktopVoiceCache(root) { _, _ ->
            val first = engines.isEmpty()
            FakeEngine(onSpeech = { _, _ -> if (first) {
                entered.complete(Unit)
                try { check(release.await(3, TimeUnit.SECONDS)); Thread.sleep(60) }
                finally { exited.complete(Unit) }
            } }, onCancel = { release.countDown() }).also(engines::add)
        }
        try {
            val reading = launch(Dispatchers.Default) { cache.read("cancelled", selection(model), "en", "F1") }
            withTimeout(3_000) { entered.await(); reading.cancelAndJoin() }
            assertTrue(exited.isCompleted)
            assertEquals(1, engines.first().closed.get())
            assertEquals(0L, outputCount(root))
            cache.read("next", selection(model), "en", "F1")
            assertEquals(2, engines.size)
        } finally { release.countDown(); cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    @Test fun concurrentReadsSerializeAndKeepTheSuccessfulOwnerResident() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-order-")
        val model = Files.createDirectory(root.resolve("models"))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val created = AtomicInteger()
        val engine = FakeEngine(onSpeech = { _, _ -> if (attempts.incrementAndGet() == 1) {
            entered.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
        } })
        val cache = DesktopVoiceCache(root) { _, _ -> created.incrementAndGet(); engine }
        try {
            val first = async(Dispatchers.Default) { cache.read("first", selection(model), "en", "F1") }
            withTimeout(3_000) { entered.await() }
            val secondEntered = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default) { secondEntered.complete(Unit); cache.read("second", selection(model), "en", "F1") }
            secondEntered.await()
            assertEquals(1, created.get()); assertEquals(1, attempts.get())
            release.countDown()
            withTimeout(3_000) { first.await(); second.await() }
            assertEquals(listOf("first:en:F1", "second:en:F1"), engine.spoken)
            assertEquals(1, created.get())
        } finally { release.countDown(); cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    @Test fun closingDuringAnActiveReadCancelsJoinsAndRejectsFutureWork() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-close-")
        val model = Files.createDirectory(root.resolve("models"))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val engine = FakeEngine(onSpeech = { _, _ -> entered.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) },
            onCancel = { release.countDown() })
        val cache = DesktopVoiceCache(root) { _, _ -> engine }
        try {
            val reading = launch(Dispatchers.Default) { cache.read("active", selection(model), "en", "F1") }
            withTimeout(3_000) {
                entered.await()
                val firstClose = async(Dispatchers.Default) { cache.closeAndJoin() }
                val secondClose = async(Dispatchers.Default) { cache.closeAndJoin() }
                firstClose.await(); secondClose.await(); reading.join()
            }
            assertTrue(reading.isCancelled)
            assertEquals(1, engine.closed.get())
            assertEquals(0L, outputCount(root))
            assertFailsWith<IllegalStateException> { cache.read("closed", selection(model), "en", "F1") }
        } finally { release.countDown(); cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    @Test fun cancellingDuringChangedPackageRetirementStillJoinsItsOldWorker() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hearth-voice-retire-")
        val model = Files.createDirectory(root.resolve("models"))
        val closing = CompletableDeferred<Unit>()
        val releaseClose = CountDownLatch(1)
        val engines = CopyOnWriteArrayList<FakeEngine>()
        val cache = DesktopVoiceCache(root) { _, _ ->
            val first = engines.isEmpty()
            FakeEngine(onClose = { if (first) { closing.complete(Unit); check(releaseClose.await(3, TimeUnit.SECONDS)) } }).also(engines::add)
        }
        try {
            cache.read("warm", selection(model), "en", "F1")
            Files.writeString(model.resolve("M5.json"), "changed")
            val replacement = launch(Dispatchers.Default) { cache.read("replacement", selection(model), "en", "F1") }
            withTimeout(3_000) { closing.await() }
            replacement.cancel()
            assertFalse(replacement.isCompleted, "Cancellation must wait while old native teardown is blocked")
            releaseClose.countDown()
            withTimeout(3_000) { replacement.join() }
            assertEquals(1, engines.size)
            assertEquals(1, engines.single().closed.get())
        } finally { releaseClose.countDown(); cache.closeAndJoin(); root.toFile().deleteRecursively() }
    }

    private class FakeEngine(
        val onSpeech: (String, SpeechCancellation) -> Unit = { _, _ -> },
        val onCancel: () -> Unit = {},
        val onClose: () -> Unit = {},
    ) : SpeechEngine {
        override val id = "cache-test"
        override val voices = listOf(SpeechVoice("F1", "First", setOf("en", "ar")), SpeechVoice("F2", "Second", setOf("en", "ar")))
        val spoken = CopyOnWriteArrayList<String>()
        val closed = AtomicInteger()
        private val active = AtomicInteger()
        override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions,
            cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
            check(active.incrementAndGet() == 1) { "Overlapping synthesis" }
            try {
                cancellation.throwIfCancelled()
                spoken += "$text:$language:${voice.id}"
                onSpeech(text, cancellation)
                cancellation.throwIfCancelled()
                emit(PcmAudio(FloatArray(200) { .1f }, 44_100))
            } finally { active.decrementAndGet() }
        }
        override fun cancel() { onCancel() }
        override fun close() { check(active.get() == 0) { "Closed during active synthesis" }; onClose(); closed.incrementAndGet() }
    }

    private fun selection(root: Path) = EngineSelection("supertonic-local", modelPath = root.toString())
    private fun outputCount(root: Path): Long = root.resolve("generated-audio").let { directory ->
        if (!Files.isDirectory(directory)) 0 else Files.list(directory).use { it.count() }
    }
}
