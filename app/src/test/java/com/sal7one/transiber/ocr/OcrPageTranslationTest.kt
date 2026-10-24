package com.sal7one.transiber.ocr

import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.common_jni.translation.EmptyTranslationOutputException
import com.sal7one.hearth.core.TranslationContentLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrPageTranslationTest {
    private fun line(text: String,x: Int=0,y: Int=0,w: Int=100,h: Int=50)=OcrLine(x,y,w,h,text,1f)
    @Test fun nearbyLinesUseOneTranslationWhileSeparateBalloonsStaySeparate()=runBlocking {
        val lines=listOf(line("Good",10,10),line("morning",12,65),line("Other bubble",240,10))
        val grouped=OcrReadingGroups.group(lines)
        assertEquals(2,grouped.size)
        assertEquals("Good\nmorning",grouped[0].text)
        assertEquals(line("Good\nmorning",10,10,102,105),grouped[0])
        var calls=0
        OcrPageTranslation.run(grouped,{true},{calls++;"translated"},{})
        assertEquals(2,calls)
    }
    @Test fun adjacentJapaneseColumnsKeepRightToLeftReadingOrder() {
        val grouped=OcrReadingGroups.group(listOf(
            line("右",120,20,24,120),line("左",84,24,24,116),line("別",20,220,24,100)))
        assertEquals(listOf("右\n左","別"),grouped.map { it.text })
        assertEquals(line("右\n左",84,20,60,120),grouped[0])
    }
    @Test fun groupingDoesNotMergeUnrelatedRowsOrUnlimitedText() {
        val rows=listOf(line("a",0,0),line("b",0,200),line("c",0,400))
        assertEquals(rows,OcrReadingGroups.group(rows))
        val close=(0..5).map {line("x",0,it*55)}
        assertEquals(listOf(4,2),OcrReadingGroups.group(close).map { it.text.count { c -> c == 'x' } })
    }
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
    @Test fun cleanupRefinementKeepsTranslationAndUsesNewestExactBox() {
        val before=line("駅",10,20)
        val refined=before.copy(confidence=.95f)
        val otherBalloon=line("駅",200,20)
        val translated=listOf(TranslatedOcrBox(before,"المحطة"))
        val result=OcrPageTranslation.refreshGeometry(translated,listOf(refined,otherBalloon))
        assertEquals("المحطة",result.single().translation)
        assertSame(refined,result.single().line)
        assertTrue(OcrPageTranslation.refreshGeometry(translated,listOf(otherBalloon)).isEmpty())
        assertTrue(OcrPageTranslation.refreshGeometry(translated,listOf(before.copy(text="新しいページ"))).isEmpty())
    }
    @Test fun generalOcrGroupedBlocksAreNotDroppedByMangaPaintRefresh() {
        val raw=listOf(line("Good",10,10),line("morning",12,65))
        val grouped=OcrReadingGroups.group(raw)
        val translated=listOf(TranslatedOcrBox(grouped.single(),"صباح الخير"))
        assertSame(translated,OcrPageTranslation.refreshGeometry(translated,raw,preservesRegions=false))
    }
    @Test fun providerFailureKeepsEarlierBoxAndPreservesActualError()=runBlocking {
        val failure=IllegalStateException("HTTP 429: quota exhausted")
        var published=emptyList<TranslatedOcrBox>();var calls=0
        val actual=runCatching {OcrPageTranslation.run(listOf(line("one"),line("two"),line("three")),{true},{if(++calls==2)throw failure else "واحد"},{published=it})}.exceptionOrNull()
        assertSame(failure,actual);assertEquals("واحد",published.single().translation);assertEquals(2,calls)
    }
    @Test fun emptyResponseIsAnErrorAndDoesNotEraseOriginal()=runBlocking {
        val source=line("original");var published=false
        val failure=runCatching {OcrPageTranslation.run(listOf(source),{true},{" "},{published=true})}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertTrue(failure!!.message!!.contains("original"));assertFalse(published)
    }
    @Test fun modelEmptyOutputForLabelDoesNotPreventArticleTranslationAndRemainsAVisiblePartialFailure()=runBlocking {
        val label=line("Search",10)
        val article=line("Coffee is a popular drink.",200)
        val cause=EmptyTranslationOutputException("Marian produced no translation for this text")
        val calls=mutableListOf<String>()
        var published=emptyList<TranslatedOcrBox>()
        val failure=runCatching {
            OcrPageTranslation.run(listOf(label,article),{true},{ text ->
                calls+=text
                if(text==label.text)throw cause
                "القهوة مشروب شائع."
            },{published=it})
        }.exceptionOrNull()
        assertEquals(listOf(label.text,article.text),calls)
        assertEquals(listOf(TranslatedOcrBox(article,"القهوة مشروب شائع.")),published)
        assertTrue(failure is IncompleteOcrTranslationException)
        assertSame(cause,failure!!.cause)
        assertTrue(failure.message!!.contains("Search"))
        assertTrue(failure.message!!.contains("original text is preserved"))
    }
    @Test fun allContentFailuresRemainFailureWithoutPublishingFakeTranslations()=runBlocking {
        var calls=0;var publications=0
        val failure=runCatching {
            OcrPageTranslation.run(listOf(line("Search"),line("Menu")),{true},{
                calls++;throw EmptyTranslationOutputException("No visible model output")
            },{publications++})
        }.exceptionOrNull() as IncompleteOcrTranslationException
        assertEquals(2,calls);assertEquals(2,failure.failures.size);assertEquals(0,publications)
    }
    @Test fun outputLimitInMiddleKeepsSourceGeometryAndAllowsLaterHealthyBoxes()=runBlocking {
        val before=line("Coffee starts here",10)
        val rejected=line("Unfinished block",200)
        val after=line("Pour the water slowly",400)
        val cause=TranslationContentLimitException("CT2 output token limit remained after safe retries")
        val calls=mutableListOf<String>()
        val updates=mutableListOf<List<TranslatedOcrBox>>()
        val failure=runCatching {
            OcrPageTranslation.run(listOf(before,rejected,after),{true},{ text ->
                calls+=text
                if(text==rejected.text)throw cause
                "translated $text"
            },{updates+=it})
        }.exceptionOrNull() as IncompleteOcrTranslationException
        assertEquals(listOf(before.text,rejected.text,after.text),calls)
        assertEquals(listOf(1,2),updates.map {it.size})
        assertEquals(listOf(before,after),updates.last().map {it.line})
        assertEquals(listOf(rejected.text to cause),failure.failures)
        assertSame(cause,failure.cause)
        assertTrue(failure.message.orEmpty().contains(rejected.text))
        assertTrue(failure.message.orEmpty().contains(cause.message!!))
        assertTrue(failure.message.orEmpty().contains("original text is preserved"))
    }
    @Test fun cachedSuccessesPublishBeforeLimitFailureAndAreNotRepeatedOnRetry()=runBlocking {
        val cache=OcrTranslationCache()
        val lines=listOf(line("Known heading",10),line("Rejected block",200),line("New article",400))
        cache.put(lines[0].text,"عنوان معروف")
        val calls=mutableListOf<String>()
        val updates=mutableListOf<List<TranslatedOcrBox>>()
        val cause=TranslationContentLimitException("CT2 could not complete this block")
        suspend fun translate(text: String): String {
            assertTrue("Known boxes appear before any slow model call",updates.isNotEmpty())
            calls+=text
            if(text==lines[1].text)throw cause
            return "مقالة جديدة".also {cache.put(text,it)}
        }
        val first=runCatching {OcrPageTranslation.run(lines,{true},::translate,{updates+=it},cached={cache[it]})}.exceptionOrNull()
        assertTrue(first is IncompleteOcrTranslationException)
        assertEquals(listOf(lines[0]),updates.first().map {it.line})
        assertEquals(listOf(lines[0],lines[2]),updates.last().map {it.line})
        assertEquals(listOf(lines[1].text,lines[2].text),calls)
        val moved=lines.map {it.copy(y=it.y+20)}
        updates.clear()
        val second=runCatching {OcrPageTranslation.run(moved,{true},::translate,{updates+=it},cached={cache[it]})}.exceptionOrNull()
        assertTrue(second is IncompleteOcrTranslationException)
        assertEquals(listOf(moved[0],moved[2]),updates.single().map {it.line})
        assertEquals(listOf(lines[1].text,lines[2].text,lines[1].text),calls)
    }
    @Test fun mixedEmptyAndLimitFailuresAreExplicitWithoutPublishingFakeBoxes()=runBlocking {
        val empty=EmptyTranslationOutputException("No visible model output")
        val limit=TranslationContentLimitException("Output token limit")
        var publications=0
        val failure=runCatching {
            OcrPageTranslation.run(listOf(line("Search"),line("Long block")),{true},{ text ->
                if(text=="Search")throw empty else throw limit
            },{publications++})
        }.exceptionOrNull() as IncompleteOcrTranslationException
        assertEquals(listOf("Search" to empty,"Long block" to limit),failure.failures)
        assertSame(empty,failure.cause)
        assertEquals(0,publications)
    }
    @Test fun stalePageDoesNotPublishOrReportItsOutputLimitFailure()=runBlocking {
        var current=true;var calls=0
        OcrPageTranslation.run(listOf(line("Long block"),line("article")),{current},{
            calls++;current=false;throw TranslationContentLimitException("Output token limit")
        },{fail("stale page must not publish")})
        assertEquals(1,calls)
    }
    @Test fun cancellationAfterAContentFailurePropagatesAndStopsRemainingBoxes()=runBlocking {
        val cancel=CancellationException("User stopped screen translation")
        val calls=mutableListOf<String>()
        val failure=runCatching {
            OcrPageTranslation.run(listOf(line("Long block"),line("Cancelled block"),line("Unstarted block")),{true},{ text ->
                calls+=text
                if(text=="Long block")throw TranslationContentLimitException("Output token limit")
                throw cancel
            },{fail("No successful translation was produced")})
        }.exceptionOrNull()
        assertSame(cancel,failure)
        assertEquals(listOf("Long block","Cancelled block"),calls)
    }
    @Test fun runtimeAndIntegrityErrorsAfterContentFailureRemainFailFast()=runBlocking {
        for(cause in listOf(IllegalStateException("CT2 model integrity changed"),IllegalStateException("CT2 runtime is unavailable"),IllegalStateException("HTTP 429: quota exhausted"))) {
            val cache=OcrTranslationCache().also {it.put("Known","translated heading")}
            var published=emptyList<TranslatedOcrBox>()
            val calls=mutableListOf<String>()
            val failure=runCatching {
                OcrPageTranslation.run(listOf(line("Known"),line("Long block"),line("Unavailable"),line("Unstarted")),{true},{ text ->
                    calls+=text
                    if(text=="Long block")throw TranslationContentLimitException("Output token limit")
                    throw cause
                },{published=it},cached={cache[it]})
            }.exceptionOrNull()
            assertSame(cause,failure)
            assertEquals(listOf("Long block","Unavailable"),calls)
            assertEquals("translated heading",published.single().translation)
        }
    }
    @Test fun stalePageDoesNotPublishOrReportItsContentFailure()=runBlocking {
        var current=true;var calls=0
        OcrPageTranslation.run(listOf(line("Search"),line("article")),{current},{
            calls++;current=false;throw EmptyTranslationOutputException("No visible model output")
        },{fail("stale page must not publish")})
        assertEquals(1,calls)
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
