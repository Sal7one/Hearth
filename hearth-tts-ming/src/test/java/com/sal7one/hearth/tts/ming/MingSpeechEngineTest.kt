package com.sal7one.hearth.tts.ming

import com.sal7one.hearth.tts.PcmAudio
import com.sal7one.hearth.tts.SpeechCancellation
import com.sal7one.hearth.tts.SpeechOptions
import com.sal7one.hearth.tts.SpeechVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MingSpeechEngineTest {
    private class FakeSynthesizer(private val samples: ShortArray) : MingSynthesizer {
        var calls = 0
        var threads = 0
        var threadName = ""
        var voice = ""
        var bundle: File? = null
        override fun synthesize(bundle: File, text: String, voice: String, outWav: File, threads: Int): String {
            calls++
            this.bundle = bundle
            this.threads = threads
            this.threadName = Thread.currentThread().name
            this.voice = voice
            outWav.writeBytes(MingTestSupport.wavBytes(samples))
            return """{"sample_rate":44100}"""
        }
    }

    private class FakeAvailability(private val available: Boolean, private val detail: String) : MingAvailability {
        override fun isAvailable(): Boolean = available
        override fun reason(): String = detail
    }

    private fun token(): SpeechCancellation =
        SpeechCancellation::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun withEngine(
        available: Boolean = true,
        synthesizer: MingSynthesizer = FakeSynthesizer(shortArrayOf(1, 2, 3)),
        block: (MingSpeechEngine, File) -> Unit,
    ) {
        val directory = MingTestSupport.tempDirectory("ming-engine-")
        val models = MingModels(directory, MingTestSupport.bundle(directory))
        val engine = MingSpeechEngine(
            directory,
            models,
            synthesizer,
            FakeAvailability(available, "Ming JNI is not packaged for this app process: libming_jni.so not found"),
        )
        try {
            block(engine, directory)
        } finally {
            engine.close()
            directory.deleteRecursively()
        }
    }

    private fun speakers(engine: MingSpeechEngine, id: String = MingCatalog.voices.first()) =
        requireNotNull(engine.voices.firstOrNull { it.id == id }) { "missing test voice" }

    @Test fun oneShotRunsOffTheCallerThreadAndEmitsBounded44kChunks() {
        val samples = ShortArray(44_100 + 123) { (it % 200 - 100).toShort() }
        val fake = FakeSynthesizer(samples)
        withEngine(synthesizer = fake) { engine, directory ->
            val emitted = mutableListOf<PcmAudio>()
            engine.synthesize("Hello from Hearth.", "en", speakers(engine), SpeechOptions(), token()) { emitted += it }
            assertEquals(2, emitted.size)
            assertEquals(listOf(44_100, 123), emitted.map { it.samples.size })
            assertTrue(emitted.all { it.sampleRate == 44_100 })
            assertEquals(1, fake.calls)
            assertNotEquals(Thread.currentThread().name, fake.threadName)
            assertTrue(fake.threadName, fake.threadName.startsWith("hearth-tts-ming-jni"))
            assertTrue(fake.threads in 1..MingCatalog.MAX_THREADS)
            assertEquals("US_James", fake.voice)
            assertEquals(directory.absolutePath, fake.bundle?.absolutePath)
        }
    }

    @Test fun chineseLocalesResolveButUnsupportedLanguagesAndLimitsFailBeforeJni() {
        val fake = FakeSynthesizer(shortArrayOf(1, 2))
        withEngine(synthesizer = fake) { engine, _ ->
            val voice = speakers(engine)
            engine.synthesize("你好，世界。", "zh-Hans", voice, SpeechOptions(), token()) { }
            assertEquals(1, fake.calls)

            fun rejected(expected: String, text: String = "ok", language: String = "en",
                         requested: SpeechVoice = voice, options: SpeechOptions = SpeechOptions()) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    engine.synthesize(text, language, requested, options, token()) { }
                }
                assertTrue("${error.message} does not contain $expected", error.message.orEmpty().contains(expected))
            }
            rejected("English and Chinese only", language = "ar")
            rejected("English and Chinese only", language = "ja-JP")
            rejected("1–300", text = "a".repeat(301))
            rejected("1–300", text = "")
            rejected("Unsupported Ming voice", requested = SpeechVoice("not-a-ming-voice", "unknown", setOf("en")))
            rejected("Unsupported Ming voice", requested = SpeechVoice("US_Emma", "Emma", setOf("ar")))
            rejected("normal speed", options = SpeechOptions(rate = 1.5f))
            assertEquals(1, fake.calls)
        }
    }

    @Test fun theAdvertisedVoicesAreExactlyTheAllowlistAndOnlySupportEnglishAndChinese() {
        withEngine { engine, _ ->
            assertEquals(MingCatalog.voices, engine.voices.map { it.id })
            engine.voices.forEach {
                assertTrue(it.supports("en"))
                assertTrue(it.supports("zh-Hans"))
                assertFalse(it.supports("ar"))
            }
            assertEquals(300, engine.maximumTextCharacters)
        }
    }

    @Test fun missingJniIsReportedWithTheLoaderReasonAndNeverCallsJni() {
        val fake = FakeSynthesizer(shortArrayOf(1))
        withEngine(available = false, synthesizer = fake) { engine, _ ->
            val error = assertThrows(IllegalStateException::class.java) {
                engine.synthesize("Hello", "en", speakers(engine), SpeechOptions(), token()) { }
            }
            assertTrue(error.message.orEmpty().contains("Ming JNI is not packaged for this app process"))
            assertEquals(0, fake.calls)
        }
    }

    @Test fun cancelBeforeARequestPreventsItAndCancelDuringTheOneShotDiscardsAudio() {
        val fake = FakeSynthesizer(shortArrayOf(1, 2, 3))
        withEngine(synthesizer = fake) { engine, _ ->
            engine.cancel()
            assertThrows(CancellationException::class.java) {
                engine.synthesize("Hello", "en", speakers(engine), SpeechOptions(), token()) { }
            }
            assertEquals(0, fake.calls)
        }

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : MingSynthesizer {
            override fun synthesize(bundle: File, text: String, voice: String, outWav: File, threads: Int): String {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test never released the fake JNI" }
                outWav.writeBytes(MingTestSupport.wavBytes(shortArrayOf(4, 5, 6)))
                return "{}"
            }
        }
        withEngine(synthesizer = blocking) { engine, _ ->
            val failure = AtomicReference<Throwable?>()
            val emitted = AtomicInteger(0)
            val caller = Thread {
                try {
                    engine.synthesize("Hello", "en", speakers(engine), SpeechOptions(), token()) { emitted.incrementAndGet() }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            caller.start()
            assertTrue("The one-shot call never started", started.await(5, TimeUnit.SECONDS))
            engine.cancel()
            release.countDown()
            caller.join(5_000)
            assertFalse("The caller did not return after cancel", caller.isAlive)
            assertTrue("Expected cancellation, got ${failure.get()}", failure.get() is CancellationException)
            assertEquals(0, emitted.get())
        }
    }

    @Test fun aMissingOrChangedBundleStopsBeforeTheJniLoad() {
        val fake = FakeSynthesizer(shortArrayOf(1))
        val directory = MingTestSupport.tempDirectory("ming-preflight-")
        try {
            val assets = MingTestSupport.bundle(directory)
            val engine = MingSpeechEngine(
                directory,
                MingModels(directory, assets),
                fake,
                FakeAvailability(true, "loaded"),
            )
            try {
                File(directory, "manifest.json").delete()
                val error = assertThrows(IllegalStateException::class.java) {
                    engine.synthesize("Hello", "en", speakers(engine), SpeechOptions(), token()) { }
                }
                assertTrue(error.message.orEmpty().contains("Install the complete Ming bundle first"))
                assertEquals(0, fake.calls)
            } finally {
                engine.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aClosedEngineRejectsFurtherRequests() {
        val fake = FakeSynthesizer(shortArrayOf(1))
        val directory = MingTestSupport.tempDirectory("ming-closed-")
        try {
            val engine = MingSpeechEngine(
                directory,
                MingModels(directory, MingTestSupport.bundle(directory)),
                fake,
                FakeAvailability(true, "loaded"),
            )
            engine.close()
            val error = assertThrows(IllegalStateException::class.java) {
                engine.synthesize("Hello", "en", speakers(engine), SpeechOptions(), token()) { }
            }
            assertTrue(error.message.orEmpty().contains("closed"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
