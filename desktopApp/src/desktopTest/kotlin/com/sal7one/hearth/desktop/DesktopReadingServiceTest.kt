package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.core.vision.*
import com.sal7one.hearth.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopReadingServiceTest {
    @Test fun serviceUsesConfiguredTranslatorAndPublishesMappedRegions() = runBlocking {
        val requests = mutableListOf<PipelineRequest>()
        val pipeline = object : PipelineService {
            override fun capabilities() = emptyList<EngineDescriptor>()
            override fun start(request: PipelineRequest): PipelineSession {
                requests += request
                return object : PipelineSession {
                    override val id = "translated"
                    override val state = MutableStateFlow(PipelineSnapshot(id, PipelineStatus.COMPLETED,
                        segments = listOf(CaptionSegment("1", "日本語", "Arabic result"))))
                    override val events = MutableSharedFlow<PipelineEvent>()
                    override suspend fun cancel() = Unit
                }
            }
        }
        val service = service(pipeline, this, FakeReader())
        try {
            assertTrue(service.capabilities().available)
            val config = PipelineConfig(sourceLanguage = "ja", targetLanguage = "ar", translation = EngineSelection("translator"),
                speech = EngineSelection("speech"), voice = EngineSelection("voice"))
            val session = service.start(IntegrationReadingRequest(image(), config, true))
            val result = withTimeout(5000) { session.state.first { it.terminal } }
            assertEquals(IntegrationReadingStatus.COMPLETED, result.status)
            assertEquals("日本語", result.regions.single().text)
            assertEquals("Arabic result", result.regions.single().translation)
            assertEquals("ar", requests.single().config.targetLanguage)
            assertNull(requests.single().config.speech)
            assertNull(requests.single().config.voice)
            session.cancel()
            assertEquals(IntegrationReadingStatus.COMPLETED, session.state.value.status)
        } finally { service.close() }
    }

    @Test fun cancellingQueuedRequestDoesNotCancelAnotherRequestsNativeReader() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = FakeReader { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val service = service(noPipeline(), this, reader)
        try {
            val first = service.start(request())
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val queued = service.start(request())
            queued.cancel()
            assertEquals(IntegrationReadingStatus.CANCELLED, queued.state.value.status)
            assertEquals(0, reader.cancels)
            release.countDown()
            assertEquals(IntegrationReadingStatus.COMPLETED, withTimeout(5000) { first.state.first { it.terminal } }.status)
        } finally { release.countDown(); service.close() }
    }

    @Test fun waitingForAdmissionNeverPublishesThePreviousImagesRegions() = runBlocking {
        lateinit var controller: DesktopReadingController
        val pipeline = noPipeline()
        val service = DesktopReadingService(pipeline, this, { Path.of("models") to "manga-page" }, { null },
            readerFactory = { config, scope -> DesktopReadingController(pipeline, config, scope) { FakeReader() }.also { controller = it } })
        var locked = false
        try {
            val first = service.start(request())
            withTimeout(5000) { first.state.first { it.terminal } }
            assertEquals(1, first.state.value.regions.size)
            DesktopReadingAdmission.mutex.lock(); locked = true
            val second = service.start(IntegrationReadingRequest(BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB),
                PipelineConfig(sourceLanguage = "ja"), false))
            withTimeout(5000) { controller.state.first { it.busy } }
            repeat(5) { yield() }
            assertEquals(20, second.state.value.width)
            assertTrue(second.state.value.regions.isEmpty(), "A queued new image must not receive the resident previous page")
            DesktopReadingAdmission.mutex.unlock(); locked = false
            withTimeout(5000) { second.state.first { it.terminal } }
            Unit
        } finally {
            if (locked) DesktopReadingAdmission.mutex.unlock()
            service.close()
        }
    }

    @Test fun activeCancellationJoinsNativeWorkAndPreservesIndependentFutureRequests() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var closed = false
        val reader = object : PageOcrReader {
            override fun recognize(argb: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return emptyList()
            }
            override fun cancel() { release.countDown() }
            override fun close() { closed = true }
        }
        val service = service(noPipeline(), this, reader)
        try {
            val session = service.start(request())
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5000) { session.cancel() }
            assertTrue(closed, "Cancellation must join and retire the owned native reader")
            assertEquals(IntegrationReadingStatus.CANCELLED, session.state.value.status)
        } finally { release.countDown(); service.close() }
        assertFalse(service.capabilities().available)
    }

    private fun service(pipeline: PipelineService, scope: CoroutineScope, native: PageOcrReader) = DesktopReadingService(
        pipeline, scope, selection = { Path.of("pinned-models") to "manga-page" }, runtimeIssue = { null },
        readerFactory = { config, parent -> DesktopReadingController(pipeline, config, parent) { native } })
    private fun request() = IntegrationReadingRequest(image(), PipelineConfig(sourceLanguage = "ja"), false)
    private fun image() = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
    private fun noPipeline() = object : PipelineService {
        override fun capabilities() = emptyList<EngineDescriptor>()
        override fun start(request: PipelineRequest): PipelineSession = error("OCR-only must not translate")
    }
    private class FakeReader(val before: () -> Unit = {}) : PageOcrReader {
        var cancels = 0
        override fun recognize(argb: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText> {
            before()
            val region = OcrRegion(OcrRect(2, 3, 12, 14), 1f)
            val text = listOf(PageText(region, "日本語"))
            progress.detected(listOf(region)); progress.recognized(text)
            return text
        }
        override fun cancel() { cancels++ }
        override fun close() = Unit
    }
}
