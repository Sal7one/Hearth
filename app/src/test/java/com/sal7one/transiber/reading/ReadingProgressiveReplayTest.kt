package com.sal7one.transiber.reading

import android.graphics.Bitmap
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.common_jni.ocr.toOcrLine
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.hearth.core.vision.OcrRect
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.PageOcrProgress
import com.sal7one.hearth.core.vision.PageText
import com.sal7one.transiber.ocr.CameraOcrController
import com.sal7one.transiber.ocr.CameraOcrState
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.translation.ConversationTranslatorSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic ticker + progressive OCR replay. No captures, network, native models or sleeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class ReadingProgressiveReplayTest {
    private val page = listOf(
        PageText(OcrRegion(OcrRect(10, 30, 210, 35), 1f), "Coffee starts with freshly roasted beans."),
        PageText(OcrRegion(OcrRect(10, 100, 210, 35), 1f), "Choose a brewing method you enjoy."),
        PageText(OcrRegion(OcrRect(10, 185, 210, 35), 1f), "Serve the coffee while it is warm."),
    )
    private fun image() = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
    private fun grey(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value

    /** Broad article geometry and a narrow animated website footer are independent. */
    private fun frame(ticker: Int = 0, scroll: Int = 0): IntArray =
        IntArray(ReadingMotion.WIDTH * ReadingMotion.HEIGHT) { index ->
            val x = index % ReadingMotion.WIDTH
            val y = index / ReadingMotion.WIDTH
            val shade = when {
                y in 112..119 && x in 2..61 -> if ((x + ticker) % 10 < 5) 30 else 245
                y in 14..102 && x in 5..58 && (y + scroll) % 12 in 2..5 -> 35
                else -> 250
            }
            grey(shade)
        }

    private class DeferredTranslator : CancellableTextTranslator {
        override val id = "replay"
        override val directions = setOf(TranslationDirection("en", "ar"))
        val calls = mutableListOf<String>()
        val pending = linkedMapOf<String, CompletableDeferred<String>>()
        val started = Channel<String>(Channel.UNLIMITED)
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            calls += text
            return CompletableDeferred<String>().also {
                pending[text] = it
                check(started.trySend(text).isSuccess)
            }.await()
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    @Test fun tickerDuringOneTwoThreeBoxProgressNeverBlanksCompletedBoxesOrRestartsOcr() = runBlocking {
        newSingleThreadContext("reader-replay-ui").use { ui ->
            newSingleThreadContext("reader-replay-native").use { native ->
                val finishRecognition = CountDownLatch(1)
                val progressReady = CompletableDeferred<PageOcrProgress>()
                val passes = AtomicInteger()
                val reader = object : OcrEngine {
                    override val preservesRegions = true
                    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int): List<OcrLine> = error("Page path required")
                    override fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress): List<OcrLine> {
                        passes.incrementAndGet()
                        progress.detected(page.map { it.region })
                        progress.recognized(page.take(1))
                        progressReady.complete(progress)
                        check(finishRecognition.await(10, TimeUnit.SECONDS)) { "Replay did not release recognition" }
                        return page.map { it.toOcrLine() }
                    }
                    override fun close() = Unit
                    override fun cancel() { finishRecognition.countDown() }
                }
                val translator = DeferredTranslator()
                val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, ui, ui, native)
                val states = mutableListOf<CameraOcrState>()
                val collector = CoroutineScope(ui).launch { controller.state.collect { states += it } }
                val motion = ReadingMotion()
                val trigger = ReadingTrigger().apply { mode = "page" }
                var invalidations = 0
                var clock = 0L
                fun tick(ticker: Int) {
                    clock += 500
                    if (motion.observe(frame(ticker), ReadingMotion.WIDTH, ReadingMotion.HEIGHT, emptyList())) {
                        invalidations++
                        controller.invalidate()
                        trigger.movement(clock)
                    }
                    assertFalse("The website ticker must not schedule another page", trigger.ready(clock))
                }
                try {
                    withContext(ui) {
                        tick(0)
                        controller.start(OcrCatalog.profile("latin"), "en", "ar", ConversationTranslatorSnapshot("replay"),
                            live = false, initial = image(), positioned = true)
                    }
                    val progress = withTimeout(10_000) { progressReady.await() }
                    assertEquals(page[0].text, withTimeout(10_000) { translator.started.receive() })
                    withContext(ui) { translator.pending.getValue(page[0].text).complete("قهوة طازجة") }
                    withTimeout(10_000) { controller.state.first { it.boxTranslations.size == 1 && !it.translating } }
                    withContext(ui) {
                        repeat(6) { tick(it * 3 + 1) }
                        assertEquals("قهوة طازجة", controller.state.value.translation)
                        progress.recognized(page.take(2))
                    }
                    withTimeout(10_000) { controller.state.first { it.lines.size == 2 && it.translating } }
                    assertEquals(page[1].text, withTimeout(10_000) { translator.started.receive() })
                    withContext(ui) {
                        assertEquals(1, controller.state.value.boxTranslations.size)
                        // The third region arrives before the second model call finishes.
                        progress.recognized(page)
                        repeat(6) { tick(it * 3 + 2) }
                        translator.pending.getValue(page[1].text).complete("طريقة التحضير")
                    }
                    withTimeout(10_000) { controller.state.first { it.boxTranslations.size == 2 && it.translating } }
                    assertEquals(page[2].text, withTimeout(10_000) { translator.started.receive() })
                    withContext(ui) { translator.pending.getValue(page[2].text).complete("قهوة دافئة") }
                    withTimeout(10_000) { controller.state.first { it.boxTranslations.size == 3 && !it.translating } }
                    finishRecognition.countDown()
                    val completed = withTimeout(10_000) {
                        controller.state.first { !it.processing && !it.translating && it.boxTranslations.size == 3 }
                    }
                    withContext(ui) {
                        assertNull(completed.error)
                        assertEquals(page.map { it.text }, translator.calls)
                        assertEquals(1, passes.get())
                        assertEquals(0, invalidations)
                        val visible = states.dropWhile { it.boxTranslations.isEmpty() }
                        assertTrue("After the first translated box, later progress must never blank it", visible.all { it.boxTranslations.isNotEmpty() })
                        assertTrue("Cumulative region progress must only add useful translations",
                            visible.zipWithNext().all { (a, b) -> b.boxTranslations.size >= a.boxTranslations.size })
                    }
                } finally {
                    finishRecognition.countDown()
                    withContext(ui) { controller.stop() }
                    withTimeout(10_000) { controller.awaitStopped() }
                    collector.cancelAndJoin()
                    controller.close()
                }
            }
        }
    }

    @Test fun tickerReplaysDoNotHideARealArticleScrollAndAdmissionRejectsStaleCompletion() {
        val motion = ReadingMotion()
        val guard = ReadingPageGuard()
        val trigger = ReadingTrigger().apply { mode = "page"; settleMs = 900; intervalMs = 4000 }
        val original = IntArray(64) { 140 }
        val initial = checkNotNull(guard.begin(original))
        motion.observe(frame(), ReadingMotion.WIDTH, ReadingMotion.HEIGHT, emptyList())
        trigger.accepted(0)
        repeat(12) { step ->
            assertFalse(motion.observe(frame(ticker = step * 3 + 1), ReadingMotion.WIDTH, ReadingMotion.HEIGHT, emptyList()))
            assertTrue(guard.isCurrent(initial))
            assertFalse(trigger.ready((step + 1) * 500L))
        }
        assertTrue("Actual article movement must still invalidate old translated geometry",
            motion.observe(frame(ticker = 5, scroll = 6), ReadingMotion.WIDTH, ReadingMotion.HEIGHT, emptyList()))
        guard.cancel()
        trigger.movement(6500)
        assertFalse("The old native page cannot complete after scrolling", guard.complete(initial))
        assertFalse(trigger.ready(7000))
        assertTrue(trigger.ready(7400))
        val next = checkNotNull(guard.begin(IntArray(64) { 80 }))
        trigger.accepted(7400)
        assertTrue(guard.complete(next))
        assertNull("Identical clean page signatures must not start another expensive run", guard.begin(IntArray(64) { 80 }))
    }
}
