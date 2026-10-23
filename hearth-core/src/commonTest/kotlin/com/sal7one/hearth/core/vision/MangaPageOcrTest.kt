package com.sal7one.hearth.core.vision

import kotlin.test.*

class MangaPageOcrTest {
    private fun hit(x: Int,y: Int,w: Int,h: Int,label: Int=1,score: Float=.9f) = ComicDetection(OcrRect(x,y,w,h),label,score)
    @Test fun separateBubblesStaySeparateAndColumnsInsideOneBubbleShareACrop() {
        val regions=MangaPageLayout.regions(listOf(
            hit(100,10,80,140,0),hit(150,30,16,90),hit(122,30,18,90),
            hit(10,10,75,140,0),hit(34,28,24,94)),200,180)
        assertEquals(2,regions.size)
        assertEquals(OcrRect(100,10,80,140),regions[0].bubble)
        assertEquals(OcrRect(10,10,75,140),regions[1].bubble)
        assertTrue(regions[0].bounds.x<=122 && regions[0].bounds.right>=166)
    }
    @Test fun emptyBubblesDoNotInvokeOcrAndCrossClassTextDuplicatesAreSuppressed() {
        val regions=MangaPageLayout.regions(listOf(hit(0,0,100,100,0),hit(15,15,40,60),hit(16,16,40,60,2,.8f),
            hit(120,10,60,30,2),hit(120,100,70,90,0)),200,200)
        assertEquals(2,regions.size)
        assertTrue(regions.any {it.bubble==null})
        assertTrue(MangaPageLayout.regions(listOf(hit(0,0,100,100,0)),200,200).isEmpty())
    }
    @Test fun rowsPrecedeLowerPanelsAndJapaneseColumnsRunRightToLeft() {
        val regions=MangaPageLayout.regions(listOf(hit(10,130,30,40,2),hit(100,10,30,70,2),hit(10,10,30,70,2)),200,200)
        assertEquals(listOf(96,6,6),regions.map {it.bounds.x})
        assertTrue(regions[2].bounds.y>regions[1].bounds.bottom)
    }
    @Test fun coordinatesAreClippedWithoutIntegerOverflowAndInvalidScoresFail() {
        val regions=MangaPageLayout.regions(listOf(hit(-5,-5,30,30,2),hit(Int.MAX_VALUE,10,100,100,2)),100,100)
        assertEquals(1,regions.size);assertEquals(0,regions.single().bounds.x)
        for(score in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,1.1f))
            assertFailsWith<IllegalArgumentException> { MangaPageLayout.regions(listOf(hit(0,0,10,10,2,score)),100,100) }
    }
    private class Detector(val hits: List<ComicDetection>): ComicRegionDetector {
        override fun detect(pixels: IntArray,width: Int,height: Int)=hits
        override fun cancel() {}
        override fun close() {}
    }
    @Test fun regionsArriveBeforeRecognitionAndPublishedSnapshotsDoNotGrow() {
        val events=mutableListOf<String>();val snapshots=mutableListOf<List<PageText>>()
        val reader=object : MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int): String {
                events+="read"; assertTrue(pixels.all {it==7});return "日本語"
            }
            override fun cancel() {}
            override fun close() {}
        }
        val ocr=MangaPageOcr(Detector(listOf(hit(5,5,20,30,2),hit(60,5,20,30,2))),reader)
        val results=ocr.recognize(IntArray(10000){7},100,100,object:PageOcrProgress {
            override fun detected(regions: List<OcrRegion>) {events+="detected";assertEquals(2,regions.size)}
            override fun recognized(text: List<PageText>) {events+="recognized";snapshots+=text}
        })
        assertEquals(listOf("detected","read","recognized","read","recognized"),events)
        assertEquals(listOf(1,2),snapshots.map {it.size});assertEquals(results,snapshots.last())
    }
    @Test fun pageMovementDropsInflightRecognitionAndStopsRemainingCrops() {
        var current=true;var calls=0;var published=0
        val reader=object:MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int):String {calls++;current=false;return "stale"}
            override fun cancel() {};override fun close() {}
        }
        val result=MangaPageOcr(Detector(listOf(hit(5,5,20,30,2),hit(60,5,20,30,2))),reader)
            .recognize(IntArray(10000),100,100,object:PageOcrProgress {
                override fun isCurrent()=current
                override fun recognized(text: List<PageText>) {published++}
            })
        assertTrue(result.isEmpty());assertEquals(1,calls);assertEquals(0,published)
    }
    @Test fun readerFailureIsPreservedWithPreviouslyPublishedRegions() {
        val failure=IllegalStateException("ORT decoder failed");var calls=0;var retained=emptyList<PageText>()
        val reader=object:MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int):String {if(++calls==2)throw failure;return "first"}
            override fun cancel() {};override fun close() {}
        }
        val actual=runCatching {MangaPageOcr(Detector(listOf(hit(5,5,20,30,2),hit(60,5,20,30,2))),reader)
            .recognize(IntArray(10000),100,100,object:PageOcrProgress {
                override fun recognized(text: List<PageText>) {retained=text}
            })}.exceptionOrNull()
        assertSame(failure,actual);assertEquals("first",retained.single().text)
    }
}
