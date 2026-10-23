package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.core.vision.*
import com.sal7one.hearth.desktop.platform.downsampleScreenImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopScreenReadingTest {
    @Test fun changedPageDuringInferenceIsDiscardedAndTheNextFrameUsesTheResidentReader() = runBlocking {
        val captures=AtomicInteger();val passes=AtomicInteger();val opened=AtomicInteger()
        val reader=reader {passes.incrementAndGet()}
        val reading=DesktopReadingController(noPipeline(),MutableStateFlow(PipelineConfig()),this) {opened.incrementAndGet();reader}
        val service=DesktopScreenReadingController(reading,this,capture={
            // The first page scrolls just as its OCR completes. All subsequent frames are stable.
            frame(if(captures.incrementAndGet()<=2)0 else 0xffffff)
        },pollMs=10,settleMs=0)
        val shown=mutableListOf<Int>()
        val observer=launch {service.state.collect {if(it.overlayReady)shown+=reading.state.value.image!!.getRGB(0,0) and 0xffffff}}
        try {
            service.start(DesktopScreenRegion(-1200,50,100,100),Path.of("models"),false,"manga-page")
            withTimeout(5_000) {service.state.first {it.completedFrames==1}}
            yield()
            assertEquals(2,passes.get(),"The moved first page must be retried instead of painted")
            assertEquals(1,opened.get(),"Stable successful frames reuse the resident native reader")
            assertTrue(shown.all {it==0xffffff},"Old screen text must never be painted over a newer page")
            service.pause();assertTrue(service.state.value.paused);assertFalse(service.state.value.overlayReady)
            service.pause()
            withTimeout(5_000) {service.state.first {it.completedFrames==2}}
            Unit
        } finally {observer.cancelAndJoin();service.close();reading.close()}
    }

    @Test fun captureFailuresStopTheServiceAndPreserveTheActualFailure() = runBlocking {
        val reading=DesktopReadingController(noPipeline(),MutableStateFlow(PipelineConfig()),this) {reader {}}
        val service=DesktopScreenReadingController(reading,this,capture={error("Screen Recording permission denied")},pollMs=10,settleMs=0)
        try {
            service.start(DesktopScreenRegion(0,0,100,100),Path.of("models"),false,"manga-page").join()
            assertFalse(service.state.value.active);assertFalse(service.state.value.overlayReady)
            assertEquals("Screen Recording permission denied",service.state.value.error)
        } finally {service.close();reading.close()}
    }

    @Test fun stopWaitsForAnOwnedNativeCallAndLateResultsCannotReopenTheOverlay() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var closed=false
        val reader=object:PageOcrReader {
            override fun recognize(pixels:IntArray,width:Int,height:Int,progress:PageOcrProgress):List<PageText> {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                progress.recognized(listOf(PageText(OcrRegion(OcrRect(1,1,10,10),1f),"old")))
                return emptyList()
            }
            override fun cancel() {}
            override fun close() {closed=true}
        }
        val reading=DesktopReadingController(noPipeline(),MutableStateFlow(PipelineConfig()),this) {reader}
        val service=DesktopScreenReadingController(reading,this,{frame(0)},pollMs=10,settleMs=0)
        try {
            val run=service.start(DesktopScreenRegion(0,0,100,100),Path.of("models"),false,"manga-page")
            withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            service.stop()
            assertFalse(service.state.value.active);assertFalse(service.state.value.overlayReady)
            assertNull(withTimeoutOrNull(100) {run.join();true},"Stopping must retain native ownership until its call returns")
            release.countDown();run.join()
            assertTrue(closed);assertTrue(reading.state.value.text.isEmpty())
            assertFalse(service.state.value.overlayReady)
        } finally {release.countDown();service.close();reading.close()}
    }

    @Test fun thumbnailComparisonIgnoresTinyCursorNoiseAndDetectsNewPageContent() {
        val original=frame(0);val cursor=frame(0).also {it.setRGB(2,2,0xffffff)}
        assertFalse(readingFrameChanged(readingFrameSignature(original),readingFrameSignature(cursor)))
        assertTrue(readingFrameChanged(readingFrameSignature(original),readingFrameSignature(frame(0xffffff))))
        val hiDpi=downsampleScreenImage(BufferedImage(4096,2160,BufferedImage.TYPE_INT_RGB))
        assertEquals(2048,hiDpi.width);assertEquals(1080,hiDpi.height)
        assertEquals(java.awt.Rectangle(-1920,-1080,1280,720),DesktopScreenRegion(-1920,-1080,1280,720).rectangle())
    }

    private fun frame(color:Int)=BufferedImage(100,100,BufferedImage.TYPE_INT_RGB).apply {
        val g=createGraphics();try {g.color=java.awt.Color(color);g.fillRect(0,0,width,height)}finally {g.dispose()}
    }
    private fun reader(action:()->Unit)=object:PageOcrReader {
        override fun recognize(pixels:IntArray,width:Int,height:Int,progress:PageOcrProgress):List<PageText> {
            action()
            val item=PageText(OcrRegion(OcrRect(1,1,10,10),1f),"日本語")
            progress.detected(listOf(item.region));progress.recognized(listOf(item));return listOf(item)
        }
        override fun cancel() {}
        override fun close() {}
    }
    private fun noPipeline()=object:PipelineService {
        override fun capabilities()=emptyList<EngineDescriptor>()
        override fun start(request:PipelineRequest):PipelineSession=error("OCR-only invoked translation")
    }
}
