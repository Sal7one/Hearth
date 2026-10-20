package com.sal7one.hearth.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArticleReaderTest {
    private val female = SpeechVoice("F1", "Female", setOf("ar", "en"), VoiceGender.FEMALE)
    private val male = SpeechVoice("M1", "Male", setOf("ar", "en"), VoiceGender.MALE)
    private open class FakeEngine : SpeechEngine {
        override val id = "fake"
        override val voices = listOf(SpeechVoice("F1", "Female", setOf("ar", "en"), VoiceGender.FEMALE))
        val calls = AtomicInteger()
        val cancels = AtomicInteger()
        val closes = AtomicInteger()
        override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
            cancellation.throwIfCancelled(); calls.incrementAndGet(); emit(PcmAudio(FloatArray(80), 8000))
        }
        override fun cancel() { cancels.incrementAndGet() }
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun `gender must be verified and fallback is explicit`() {
        assertEquals(male, SpeechVoices.resolve(listOf(female, male), "ar-SA", VoicePreference(gender = VoiceGender.MALE)).voice)
        assertThrows(IllegalArgumentException::class.java) {
            SpeechVoices.resolve(listOf(female), "ar", VoicePreference(gender = VoiceGender.MALE))
        }
        val fallback = SpeechVoices.resolve(listOf(female), "ar", VoicePreference(gender = VoiceGender.MALE, fallback = VoiceFallback.ALLOW_SAME_LANGUAGE))
        assertEquals(female, fallback.voice)
        assertNotNull(fallback.fallbackReason)
        assertThrows(IllegalArgumentException::class.java) { SpeechVoices.resolve(listOf(female), "ja", VoicePreference()) }
    }

    @Test fun `article chunking keeps Arabic and supplementary code points`() {
        val article = ("مرحبا بالعالم! نقرأ المقال بالعربية؛ Then English 👩🏽‍💻 with 3.14 and Dr. Jones.\n").repeat(120)
        val parts = ArticleText.chunks(article, 80).toList()
        assertTrue(parts.all { it.length <= 80 && it.isNotBlank() })
        assertEquals(article.filterNot(Char::isWhitespace), parts.joinToString("").filterNot(Char::isWhitespace))
        parts.forEach { part ->
            assertFalse(Character.isLowSurrogate(part.first()))
            assertFalse(Character.isHighSurrogate(part.last()))
        }
        val unbroken = "𐐀".repeat(600)
        assertEquals(unbroken, ArticleText.chunks(unbroken, 33).joinToString(""))
        assertThrows(IllegalArgumentException::class.java) { ArticleText.chunks("x".repeat(200_001)) }
    }

    @Test fun `completed reader can be reused and closes once`() = runBlocking {
        val engine = FakeEngine(); val reader = ArticleReader(engine)
        val first = reader.read("أهلا وسهلا", "ar").toList()
        val second = reader.read("Read the next article.", "en").toList()
        assertEquals(1, first.filterIsInstance<ArticleSpeech.VoiceSelected>().size)
        assertEquals(1, second.filterIsInstance<ArticleSpeech.Audio>().size)
        assertEquals(2, engine.calls.get())
        assertEquals(0, engine.cancels.get())
        reader.closeAndJoin(); reader.closeAndJoin()
        assertEquals(1, engine.closes.get())
    }

    @Test fun `native errors and empty speech are failures`() = runBlocking {
        val failure = object : FakeEngine() {
            override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) { error("vocoder tensor mismatch") }
        }
        val reader = ArticleReader(failure)
        try { reader.read("مرحبا", "ar").toList(); fail("Expected engine failure") }
        catch (expected: IllegalStateException) { assertEquals("vocoder tensor mismatch", expected.message) }
        finally { reader.closeAndJoin() }
        val empty = ArticleReader(object : FakeEngine() {
            override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) = Unit
        })
        try { empty.read("مرحبا", "ar").toList(); fail("Expected empty speech failure") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("produced no audio")) }
        finally { empty.closeAndJoin() }
    }

    @Test(timeout = 10000) fun `slow consumer bounds production and cancellation releases native owner`() = runBlocking {
        val emitted = AtomicInteger(); val closedWhileRunning = AtomicInteger()
        val active = CountDownLatch(1); val finished = CountDownLatch(1)
        val nativeCancelled = CountDownLatch(1)
        val engine = object : FakeEngine() {
            override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
                active.countDown()
                try { repeat(100) { cancellation.throwIfCancelled(); emitted.incrementAndGet(); emit(PcmAudio(FloatArray(800), 8000)) } }
                finally {
                    // Keep a native owner alive until cancel() reaches it. Without this
                    // handshake, aborting the blocked channel send may finish synthesis
                    // before the reader checks whether native cancellation is still needed.
                    try {
                        if (cancellation.isCancelled) assertTrue("Native cancel must release inference", nativeCancelled.await(5, TimeUnit.SECONDS))
                    } finally { finished.countDown() }
                }
            }
            override fun cancel() { super.cancel(); nativeCancelled.countDown() }
            override fun close() { if (finished.count != 0L) closedWhileRunning.incrementAndGet(); super.close() }
        }
        val reader = ArticleReader(engine); val receiving = CompletableDeferred<Unit>()
        val collection = launch(Dispatchers.Default) {
            reader.read("مرحبا", "ar").collect { if (it is ArticleSpeech.Audio) { receiving.complete(Unit); delay(100_000) } }
        }
        withTimeout(5000) { receiving.await() }
        assertTrue(active.await(1, TimeUnit.SECONDS))
        delay(50)
        assertTrue("PCM production must stay bounded", emitted.get() <= 3)
        withTimeout(5000) { collection.cancelAndJoin() }
        withTimeout(5000) { reader.closeAndJoin() }
        assertTrue("A live native owner must receive cancellation", engine.cancels.get() > 0)
        assertEquals(0, closedWhileRunning.get())
        assertEquals(1, engine.closes.get())
    }

    @Test fun `PCM rejects empty oversized and nonfinite frames`() {
        assertThrows(IllegalArgumentException::class.java) { PcmAudio(floatArrayOf(), 8000) }
        assertThrows(IllegalArgumentException::class.java) { PcmAudio(FloatArray(8001), 8000) }
        assertThrows(IllegalArgumentException::class.java) { PcmAudio(floatArrayOf(Float.NaN), 8000) }
    }

    @Test fun `adapter cannot mutate PCM that has already been emitted`() = runBlocking {
        val engine = object : FakeEngine() {
            override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
                val borrowed = floatArrayOf(.25f, -.5f)
                emit(PcmAudio(borrowed, 8000))
                borrowed.fill(0f)
            }
        }
        val reader = ArticleReader(engine)
        val events = reader.read("مرحبا", "ar").toList()
        assertArrayEquals(floatArrayOf(.25f, -.5f), events.filterIsInstance<ArticleSpeech.Audio>().single().pcm.samples, 0f)
        reader.closeAndJoin()
    }

    @Test(timeout = 10000) fun `stop finishes native ownership before another article starts`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val owners = AtomicInteger(); val maximumOwners = AtomicInteger()
        val engine = object : FakeEngine() {
            override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
                val current = owners.incrementAndGet(); maximumOwners.updateAndGet { maxOf(it, current) }
                try {
                    if (calls.getAndIncrement() == 0) { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
                    cancellation.throwIfCancelled(); emit(PcmAudio(FloatArray(80), 8000))
                } finally { owners.decrementAndGet() }
            }
            override fun cancel() { super.cancel(); release.countDown() }
        }
        val reader = ArticleReader(engine)
        val active = launch(Dispatchers.Default) { reader.read("المقال الأول", "ar").collect() }
        withTimeout(5000) { entered.await() }
        reader.stop()
        withTimeout(5000) { active.join() }
        val next = reader.read("Second article", "en").toList()
        assertEquals(1, next.filterIsInstance<ArticleSpeech.Audio>().size)
        assertEquals(1, maximumOwners.get())
        reader.closeAndJoin()
    }

    @Test(timeout = 10000) fun `closing a reader aborts a full PCM queue before native teardown`() = runBlocking {
        val receiving = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val engine = object : FakeEngine() {
            override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
                running.incrementAndGet()
                try { repeat(100) { cancellation.throwIfCancelled(); emit(PcmAudio(FloatArray(80), 8000)) } }
                finally { running.decrementAndGet() }
            }
            override fun close() { assertEquals("Native inference must finish before engine destruction", 0, running.get()); super.close() }
        }
        val reader = ArticleReader(engine)
        val collection = launch(Dispatchers.Default) {
            reader.read("مرحبا", "ar").collect { if (it is ArticleSpeech.Audio) { receiving.complete(Unit); delay(100_000) } }
        }
        withTimeout(5000) { receiving.await() }
        reader.close()
        withTimeout(5000) { reader.closeAndJoin(); collection.join() }
        assertEquals(1, engine.closes.get())
        assertEquals(0, running.get())
    }
}
