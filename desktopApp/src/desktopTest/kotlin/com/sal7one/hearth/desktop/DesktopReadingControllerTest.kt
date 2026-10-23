package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.core.vision.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopReadingControllerTest {
    @Test fun cropCoordinatesAndTranslationsUseTheSharedConfigurationWithoutVoice() = runBlocking {
        val config = MutableStateFlow(PipelineConfig(speech=EngineSelection("speech"),translation=EngineSelection("translator"),
            voice=EngineSelection("voice"),sourceLanguage="en",targetLanguage="ar"))
        val original = config.value
        val requests = mutableListOf<PipelineRequest>()
        var joined = 0
        val pipeline = object:PipelineService {
            override fun capabilities() = emptyList<EngineDescriptor>()
            override fun start(request: PipelineRequest):PipelineSession {
                requests += request
                return session(PipelineSnapshot("result",PipelineStatus.COMPLETED,
                    segments=listOf(CaptionSegment("1",(request.input as PipelineInput.Text).text,"مرحبا")))) {joined++}
            }
        }
        val reader = FakeReader()
        val controller = DesktopReadingController(pipeline,config,this) {reader}
        val path = image()
        try {
            controller.openImage(path); controller.awaitIdle()
            controller.selectArea(OcrRect(20,30,50,60))
            controller.read(Path.of("models"),true); controller.awaitIdle()
            assertNull(controller.state.value.error)
            val region = controller.state.value.regions.single().bounds
            assertEquals(OcrRect(22,33,12,14),region)
            assertEquals("مرحبا",controller.state.value.translations[region])
            assertEquals(1,joined)
            assertEquals("ja",requests.single().config.sourceLanguage)
            assertEquals("ar",requests.single().config.targetLanguage)
            assertNull(requests.single().config.speech); assertNull(requests.single().config.voice)
            assertEquals(original,config.value)
            controller.read(Path.of("models"),false);controller.awaitIdle()
            assertEquals(1,requests.size,"OCR-only does not invoke a translator")
        } finally {controller.close();Files.deleteIfExists(path)}
        assertTrue(reader.closed)
    }

    @Test fun cancellationJoinsNativeWorkBeforeOpeningAnotherPage() = runBlocking {
        val started = CountDownLatch(1);val release = CountDownLatch(1)
        val reader = FakeReader {progress ->
            started.countDown()
            check(release.await(10,TimeUnit.SECONDS))
            // Mimic a late native return. The controller must reject its publication.
            progress.detected(listOf(OcrRegion(OcrRect(1,1,10,10),1f)))
        }
        val controller = DesktopReadingController(noPipeline(),MutableStateFlow(PipelineConfig()),this) {reader}
        val path = image()
        try {
            controller.openImage(path);controller.awaitIdle()
            controller.read(Path.of("models"),false)
            withContext(Dispatchers.IO) {assertTrue(started.await(10,TimeUnit.SECONDS))}
            controller.cancel()
            controller.openImage(path)
            yield()
            controller.openImage(path) // Replace a request that is itself waiting for cleanup.
            assertTrue(controller.state.value.busy)
            assertNull(withTimeoutOrNull(100) {controller.state.first {!it.busy}}, "A replacement must still wait for the original native owner")
            release.countDown();controller.awaitIdle()
            assertTrue(controller.state.value.regions.isEmpty())
            assertTrue(reader.closed)
        } finally {release.countDown();controller.close();Files.deleteIfExists(path)}
    }

    @Test fun translationErrorsPreserveRecognizedTextAndSurfaceTheProviderFailure() = runBlocking {
        val pipeline = object:PipelineService {
            override fun capabilities() = emptyList<EngineDescriptor>()
            override fun start(request: PipelineRequest) = session(PipelineSnapshot("bad",PipelineStatus.FAILED,error="Provider quota exceeded")) {}
        }
        val controller = DesktopReadingController(pipeline,MutableStateFlow(PipelineConfig(translation=EngineSelection("cloud"))),this) {FakeReader()}
        val path = image()
        try {
            controller.openImage(path);controller.awaitIdle()
            controller.read(Path.of("models"),true);controller.awaitIdle()
            assertEquals("Provider quota exceeded",controller.state.value.error)
            assertEquals("こんにちは",controller.state.value.text.single().text)
            assertTrue(controller.state.value.translations.isEmpty())
        } finally {controller.close();Files.deleteIfExists(path)}
    }

    @Test fun largeImagesAreSubsampledBeforeNativePixelAllocation() {
        val path = Files.createTempFile("hearth-reading-large",".png")
        try {
            ImageIO.write(BufferedImage(4100,100,BufferedImage.TYPE_BYTE_GRAY),"PNG",path.toFile())
            val decoded = decodeReadingImage(path)
            assertTrue(decoded.width <= 2048 && decoded.height <= 2048)
            Files.write(path,byteArrayOf(0))
            assertFailsWith<IllegalStateException> {decodeReadingImage(path)}
        } finally {Files.deleteIfExists(path)}
    }

    private class FakeReader(val run: ((PageOcrProgress)->Unit)?=null):PageOcrReader {
        var closed = false
        override fun recognize(pixels:IntArray,width:Int,height:Int,progress:PageOcrProgress):List<PageText> {
            run?.let {it(progress);return emptyList()}
            val region = OcrRegion(OcrRect(2,3,12,14),.9f)
            progress.detected(listOf(region))
            return listOf(PageText(region,"こんにちは")).also(progress::recognized)
        }
        override fun cancel() {}
        override fun close() {closed=true}
    }
    private fun image():Path = Files.createTempFile("hearth-reading",".png").also {
        ImageIO.write(BufferedImage(120,160,BufferedImage.TYPE_INT_RGB),"PNG",it.toFile())
    }
    private suspend fun DesktopReadingController.awaitIdle() = withTimeout(15_000) {state.first {!it.busy}}
    private fun noPipeline() = object:PipelineService {
        override fun capabilities() = emptyList<EngineDescriptor>()
        override fun start(request: PipelineRequest):PipelineSession = error("OCR-only invoked the pipeline")
    }
    private fun session(snapshot:PipelineSnapshot,close: suspend ()->Unit) = object:PipelineSession {
        override val id = snapshot.id
        override val state = MutableStateFlow(snapshot)
        override val events = MutableSharedFlow<PipelineEvent>()
        override suspend fun cancel() = close()
    }
}
