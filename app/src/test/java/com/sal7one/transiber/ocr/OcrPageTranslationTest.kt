package com.sal7one.transiber.ocr

import com.sal7one.common_jni.ocr.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrPageTranslationTest {
    private fun line(text: String,x: Int=0,y: Int=0,w: Int=100,h: Int=50)=OcrLine(x,y,w,h,text,1f)
    @Test fun duplicateAndMultilineOutputsStayAttachedToTheirOwnBoxes()=runBlocking {
        val lines=listOf(line("駅",10),line("駅",200))
        val updates=mutableListOf<List<TranslatedOcrBox>>()
        var calls=0
        OcrPageTranslation.run(lines,{true},{calls++;"station\n$calls"},{updates+=it})
        assertEquals(listOf(1,2),updates.map {it.size})
        assertEquals(lines,updates.last().map {it.line})
        assertEquals(listOf("station\n1","station\n2"),updates.last().map {it.translation})
        assertEquals(1,updates.first().size) // Published snapshots do not grow when the next result arrives.
    }
    @Test fun changedPageDropsInflightResultAndDoesNotStartRemainingBoxes()=runBlocking {
        var current=true;var calls=0;var published=0
        OcrPageTranslation.run(listOf(line("one"),line("two")),{current},{calls++;current=false;"stale"},{published++})
        assertEquals(1,calls);assertEquals(0,published)
    }
    @Test fun providerFailureKeepsEarlierBoxAndPreservesActualError()=runBlocking {
        val failure=IllegalStateException("HTTP 429: quota exhausted")
        var published=emptyList<TranslatedOcrBox>();var calls=0
        val actual=runCatching {OcrPageTranslation.run(listOf(line("one"),line("two")),{true},{if(++calls==2)throw failure else "واحد"},{published=it})}.exceptionOrNull()
        assertSame(failure,actual);assertEquals("واحد",published.single().translation)
    }
    @Test fun emptyResponseIsAnErrorAndDoesNotEraseOriginal()=runBlocking {
        val source=line("original");var published=false
        val failure=runCatching {OcrPageTranslation.run(listOf(source),{true},{" "},{published=true})}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertTrue(failure!!.message!!.contains("original"));assertFalse(published)
    }
    @Test fun oversizedPageDoesNotStartAnyTranslation()=runBlocking {
        for(lines in listOf(List(65){line("a")},listOf(line("a".repeat(3001))))) {
            var calls=0
            assertTrue(runCatching {OcrPageTranslation.run(lines,{true},{calls++;it},{})}.isFailure)
            assertEquals(0,calls)
        }
    }
    @Test fun cropOffsetAndWidePageLetterboxAreAppliedExactly() {
        assertEquals(PixelCrop(100,250,200,300),OcrPageLayout.box(line("x",0,0,100,50),PixelCrop(100,50,300,150),400,200,400,600))
    }
    @Test fun verticalBoxesMapToLandscapePreviewWithoutChangingOrder() {
        assertEquals(PixelCrop(290,40,310,240),OcrPageLayout.box(line("縦",10,20,20,200),PixelCrop(80,20,140,300),200,400,600,400))
    }
    @Test fun glyphCoverMarginRemainsInsideTheSelectedCrop() {
        assertEquals(PixelCrop(100,240,230,311),OcrPageLayout.box(line("x",0,0,100,60),PixelCrop(100,40,250,120),400,200,400,600,coverEdges=true))
    }
    @Test fun tallColumnDoesNotExpandItsMaskByItsFullHeight() {
        assertEquals(PixelCrop(286,30,314,250),OcrPageLayout.box(line("縦",10,20,20,200),PixelCrop(80,20,140,300),200,400,600,400,coverEdges=true))
    }
    @Test fun offImageAndOverflowedNativeCoordinatesAreClippedOrRejected() {
        val crop=PixelCrop(0,0,200,100)
        assertEquals(PixelCrop(0,0,20,30),OcrPageLayout.box(line("x",-10,-20,30,50),crop,200,100,200,100))
        assertNull(OcrPageLayout.box(line("x",Int.MAX_VALUE,0,100,20),crop,200,100,200,100))
        assertNull(OcrPageLayout.box(line("x",0,0,-1,20),crop,200,100,200,100))
        assertNull(OcrPageLayout.box(line("x"),crop,200,100,0,100))
    }
}
