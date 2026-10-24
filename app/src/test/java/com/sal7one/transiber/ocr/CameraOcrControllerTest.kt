package com.sal7one.transiber.ocr

import android.graphics.Bitmap
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.common_jni.translation.EmptyTranslationOutputException
import com.sal7one.hearth.core.vision.OcrRect
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.PageOcrProgress
import com.sal7one.hearth.core.vision.PageText
import com.sal7one.transiber.translation.ConversationTranslatorSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the actual frame and translation workers, not a second copy of their ticket logic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class CameraOcrControllerTest {
    private val profile = OcrCatalog.profile("latin")
    private val route = ConversationTranslatorSnapshot("test")
    private fun image() = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
    private fun line(text: String) = OcrLine(10, 20, 180, 30, text, 1f)

    private class Reader(var read: () -> List<OcrLine>) : OcrEngine {
        var closed = 0
        var cancelled = 0
        override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) = read()
        override fun close() { closed++ }
        override fun cancel() { cancelled++ }
    }
    private class Translator : CancellableTextTranslator {
        override val id = "test"
        override val directions = setOf(TranslationDirection("en", "ar"))
        val calls = mutableListOf<String>()
        val pending = mutableListOf<CompletableDeferred<String>>()
        var closed = 0
        var cancelled = 0
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            calls += text
            return CompletableDeferred<String>().also { pending += it }.await()
        }
        override fun close() { closed++ }
        override fun cancel() { cancelled++ }
    }

    @Test fun repeatedLiveFramesCannotCompleteThePageBeforeItsTranslation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = Reader { listOf(line("Coffee starts here")) }
        val translator = Translator()
        val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, dispatcher, dispatcher, dispatcher)
        try {
            controller.start(profile, "en", "ar", route, live = true)
            runCurrent()
            repeat(2) { controller.offer(image()); runCurrent() }
            assertEquals(listOf("Coffee starts here"), translator.calls)
            assertTrue(controller.state.value.translating)
            controller.offer(image()); runCurrent()
            assertTrue("A third unchanged frame must not report idle while the second is translating", controller.state.value.translating)
            translator.pending.single().complete("تبدأ القهوة هنا")
            runCurrent()
            assertEquals("تبدأ القهوة هنا", controller.state.value.translation)
            assertFalse(controller.state.value.translating)
        } finally { controller.close(); runCurrent() }
        assertEquals(1, reader.closed)
        assertEquals(1, translator.closed)
    }

    @Test fun retryAfterAProviderCancelsOneCallKeepsTheTranslationWorkerAlive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = Reader { listOf(line("Coffee starts here")) }
        val translator = Translator()
        val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, dispatcher, dispatcher, dispatcher)
        try {
            controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true)
            runCurrent()
            translator.pending.single().completeExceptionally(CancellationException("Provider cancelled this request"))
            runCurrent()
            assertEquals("Provider cancelled this request", controller.state.value.error)
            assertFalse(controller.state.value.translating)
            assertTrue(controller.state.value.running)
            controller.offer(image(), captured = true); runCurrent()
            assertEquals(2, translator.calls.size)
            translator.pending.last().complete("تبدأ القهوة هنا")
            runCurrent()
            assertNull(controller.state.value.error)
            assertEquals("تبدأ القهوة هنا", controller.state.value.translation)
            assertEquals(1, controller.state.value.boxTranslations.size)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun staleRecognitionFailureDoesNotPoisonTheResidentReaderOrNextCapture() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var controller: CameraOcrController
        var calls = 0
        val reader = Reader {
            if (++calls == 1) {
                controller.invalidate()
                error("Obsolete native frame failure")
            }
            listOf(line("A new coffee article"))
        }
        val translator = Translator()
        var acquired = 0
        var released = 0
        controller = CameraOcrController({ reader }, { translator }, { acquired++; AutoCloseable { released++ } }, dispatcher, dispatcher, dispatcher)
        try {
            controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true)
            runCurrent()
            assertTrue(controller.state.value.running)
            assertFalse(controller.state.value.processing)
            assertNull(controller.state.value.error)
            controller.offer(image(), captured = true); runCurrent()
            translator.pending.single().complete("مقالة جديدة عن القهوة")
            runCurrent()
            assertEquals("مقالة جديدة عن القهوة", controller.state.value.translation)
            assertEquals(1, acquired)
            assertEquals(0, reader.closed)
        } finally { controller.close(); runCurrent() }
        assertEquals(1, reader.closed)
        assertEquals(1, released)
    }

    @Test fun emptyMarianLabelPreservesOriginalAndTranslatesRestOfPageWithoutRetiringWorker() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val label = line("Search")
        val article = line("Coffee is a popular drink.").copy(y = 200)
        val reader = Reader { listOf(label, article) }
        val translator = Translator()
        val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, dispatcher, dispatcher, dispatcher)
        try {
            controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true)
            runCurrent()
            translator.pending.single().completeExceptionally(EmptyTranslationOutputException("Marian produced no translation for this text"))
            runCurrent()
            assertEquals(listOf(label.text, article.text), translator.calls)
            translator.pending.last().complete("القهوة مشروب شائع.")
            runCurrent()
            val partial = controller.state.value
            assertTrue(partial.running)
            assertFalse(partial.translating)
            assertTrue(partial.error.orEmpty().contains("Search"))
            assertEquals(listOf(label, article), partial.lines)
            assertEquals(listOf(article), partial.boxTranslations.map { it.line })
            assertEquals("القهوة مشروب شائع.", partial.translation)

            reader.read = { listOf(article) }
            controller.offer(image(), captured = true); runCurrent()
            assertNull(controller.state.value.error)
            assertEquals("القهوة مشروب شائع.", controller.state.value.translation)
            assertEquals(2, translator.calls.size) // Successful article result stays cached.
            assertEquals(0, translator.closed)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun ignoredTickerIsExcludedFromProgressiveRecognitionAndTranslationWithoutErasingArticle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ticker = OcrRegion(OcrRect(0,10,240,20),1f)
        val body = OcrRegion(OcrRect(10,100,180,30),1f)
        val reader = object : OcrEngine {
            override val preservesRegions = true
            override fun recognize(pixels: IntArray,width: Int,height: Int,maxSide: Int) = error("Page path required")
            override fun recognizePage(pixels: IntArray,width: Int,height: Int,maxSide: Int,progress: PageOcrProgress): List<OcrLine> {
                progress.detected(listOf(ticker,body))
                progress.recognized(listOf(PageText(ticker,"FREE SHIPPING"),PageText(body,"Coffee article")))
                return listOf(OcrLine(0,10,240,20,"FREE SHIPPING",1f,ticker),OcrLine(10,100,180,30,"Coffee article",1f,body))
            }
            override fun close() {}
            override fun cancel() {}
        }
        val translator = Translator()
        val controller = CameraOcrController({reader},{translator},{AutoCloseable {}},dispatcher,dispatcher,dispatcher)
        try {
            controller.start(profile,"en","ar",route,live=false,initial=image(),positioned=true,
                excludedAreas=listOf(PixelCrop(0,0,240,40)))
            runCurrent()
            assertEquals(listOf("Coffee article"),translator.calls)
            assertEquals(listOf(body),controller.state.value.regions)
            assertEquals("Coffee article",controller.state.value.text)
            translator.pending.single().complete("مقالة عن القهوة");runCurrent()
            assertEquals("مقالة عن القهوة",controller.state.value.translation)
            assertEquals(listOf("Coffee article"),controller.state.value.boxTranslations.map {it.line.text})
            assertNull(controller.state.value.error)
        } finally { controller.close();runCurrent() }
    }

    @Test fun oldTranslationsAndTheirErrorsCannotReplaceANewerCapture() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var words = "First coffee page"
        val reader = Reader { listOf(line(words)) }
        val translator = Translator()
        val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, dispatcher, dispatcher, dispatcher)
        try {
            controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true)
            runCurrent()
            words = "Latest coffee page"
            controller.invalidate()
            controller.offer(image(), captured = true); runCurrent()
            assertEquals("Latest coffee page", controller.state.value.text)
            translator.pending.first().completeExceptionally(IllegalStateException("Obsolete provider failure"))
            runCurrent()
            assertNull(controller.state.value.error)
            assertTrue(controller.state.value.translating)
            assertEquals(listOf("First coffee page", "Latest coffee page"), translator.calls)
            translator.pending.last().complete("آخر صفحة عن القهوة")
            runCurrent()
            assertEquals("آخر صفحة عن القهوة", controller.state.value.translation)
            assertEquals("Latest coffee page", controller.state.value.boxTranslations.single().line.text)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun blankTranslationSurfacesARealErrorAndManualRetryCanRecover() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = Reader { listOf(line("Coffee starts here")) }
        val translator = Translator()
        val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, dispatcher, dispatcher, dispatcher)
        try {
            controller.start(profile, "en", "ar", route, live = false, initial = image())
            runCurrent()
            translator.pending.single().complete("   ")
            runCurrent()
            assertEquals("Translation returned empty text", controller.state.value.error)
            assertFalse(controller.state.value.translating)
            controller.offer(image(), captured = true); runCurrent()
            translator.pending.last().complete("تبدأ القهوة هنا"); runCurrent()
            assertNull(controller.state.value.error)
            assertEquals("تبدأ القهوة هنا", controller.state.value.translation)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun stoppingDuringTranslatorLoadClosesBeforeReleasingTheLease() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = Reader { listOf(line("Coffee starts here")) }
        val translator = Translator()
        var leased = 0
        lateinit var controller: CameraOcrController
        controller = CameraOcrController({ reader }, { controller.stop(); translator },
            { leased++; AutoCloseable { assertEquals(1, reader.closed); assertEquals(1, translator.closed); leased-- } },
            dispatcher, dispatcher, dispatcher)
        controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true)
        runCurrent()
        assertFalse(controller.state.value.running)
        assertFalse(controller.state.value.closing)
        assertTrue(translator.calls.isEmpty())
        assertEquals(0, leased)
        controller.close(); runCurrent()
    }

    @Test fun progressiveRecognitionKeepsValidAggregateAndClearsItWhenEveryBoxChanges() = runBlocking {
        newSingleThreadContext("reader-aggregate-controller-test").use { ui ->
            newSingleThreadContext("reader-aggregate-native-test").use { native ->
                val releaseNative = CountDownLatch(1)
                val progressReady = CompletableDeferred<PageOcrProgress>()
                val translator = Translator()
                val region = OcrRegion(OcrRect(10, 20, 180, 30), 1f)
                val reader = object : OcrEngine {
                    override val preservesRegions = true
                    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) = error("Page path required")
                    override fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress): List<OcrLine> {
                        progress.detected(listOf(region))
                        progress.recognized(listOf(PageText(region, "Coffee")))
                        progressReady.complete(progress)
                        check(releaseNative.await(10, TimeUnit.SECONDS)) { "Test did not release native recognition" }
                        return listOf(line("Coffee roasting"))
                    }
                    override fun cancel() { releaseNative.countDown() }
                    override fun close() {}
                }
                val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, ui, ui, native)
                try {
                    withContext(ui) { controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true) }
                    val progress = withTimeout(10_000) { progressReady.await() }
                    withTimeout(10_000) { controller.state.first { it.translating } }
                    withContext(ui) { translator.pending.single().complete("قهوة") }
                    withTimeout(10_000) { controller.state.first { it.translation == "قهوة" && !it.translating } }
                    withContext(ui) {
                        // Confidence/paint refinements retain the exact source box and
                        // its useful translation while recognition is still processing.
                        progress.recognized(listOf(PageText(region.copy(confidence = .9f), "Coffee")))
                        assertTrue(controller.state.value.processing)
                        assertEquals("قهوة", controller.state.value.translation)
                        assertEquals("قهوة", controller.state.value.boxTranslations.single().translation)

                        // A corrected source invalidates its old geometry-keyed result.
                        // Aggregate text must disappear with the authoritative box list.
                        progress.recognized(listOf(PageText(region, "Coffee roasting")))
                        assertEquals("Coffee roasting", controller.state.value.text)
                        assertTrue(controller.state.value.boxTranslations.isEmpty())
                        assertEquals("", controller.state.value.translation)
                        assertTrue(controller.state.value.processing)
                        assertNull(controller.state.value.error)
                    }
                } finally {
                    releaseNative.countDown()
                    withContext(ui) { controller.stop() }
                    withTimeout(10_000) { controller.awaitStopped() }
                    controller.close()
                }
            }
        }
    }

    @Test fun progressiveIntermediateAndFinalWorkNeverExposePrematureCompletion() = runBlocking {
        // The native reader genuinely runs concurrently with a suspended translator.
        // Holding its final result reproduces the real Koharu callback ordering.
        newSingleThreadContext("reader-controller-test").use { ui ->
            newSingleThreadContext("reader-native-test").use { native ->
                val releaseNative = CountDownLatch(1)
                val enteredNative = CompletableDeferred<Unit>()
                val translator = Translator()
                val region = OcrRegion(OcrRect(10, 20, 180, 30), 1f)
                val words = listOf(PageText(region, "Coffee starts here"))
                val reader = object : OcrEngine {
                    override val preservesRegions = true
                    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) = error("Page path required")
                    override fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress): List<OcrLine> {
                        progress.detected(listOf(region))
                        progress.recognized(words)
                        enteredNative.complete(Unit)
                        check(releaseNative.await(10, TimeUnit.SECONDS)) { "Test did not release native recognition" }
                        return listOf(line("Coffee starts here"))
                    }
                    override fun cancel() { releaseNative.countDown() }
                    override fun close() {}
                }
                val controller = CameraOcrController({ reader }, { translator }, { AutoCloseable {} }, ui, ui, native)
                try {
                    withContext(ui) { controller.start(profile, "en", "ar", route, live = false, initial = image(), positioned = true) }
                    withTimeout(10_000) { enteredNative.await(); controller.state.first { it.translating } }
                    withContext(ui) {
                        assertTrue(controller.state.value.processing)
                        assertEquals(1, translator.pending.size)
                        translator.pending.single().completeExceptionally(IllegalStateException("Transient intermediate request failure"))
                    }
                    withTimeout(10_000) { controller.state.first { it.error != null && !it.translating } }
                    assertTrue(controller.state.value.processing)
                    releaseNative.countDown()
                    withTimeout(10_000) { controller.state.first { !it.processing && it.translating } }
                    withContext(ui) {
                        assertNull("A final retry must not retain an intermediate request's error", controller.state.value.error)
                        assertEquals(2, translator.pending.size)
                        translator.pending.last().complete("تبدأ القهوة هنا")
                    }
                    val result = withTimeout(10_000) { controller.state.first { !it.processing && !it.translating && it.translation.isNotBlank() } }
                    assertNull(result.error)
                    assertEquals("تبدأ القهوة هنا", result.boxTranslations.single().translation)
                } finally {
                    releaseNative.countDown()
                    withContext(ui) { controller.stop() }
                    withTimeout(10_000) { controller.awaitStopped() }
                    controller.close()
                }
            }
        }
    }
}
