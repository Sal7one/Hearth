package com.sal7one.hearth.core.vision

import kotlin.test.*

class KoharuPageSceneTest {
    private fun mask(x: Int,y: Int,w: Int,h: Int,width: Int=200,height: Int=200)=
        ComicMask(IntArray(h*3).also {out -> repeat(h){out[it*3]=y+it;out[it*3+1]=x;out[it*3+2]=w}},width,height)
    private fun element(x: Int,y: Int,w: Int,h: Int,label: Int)=ComicDetection(OcrRect(x,y,w,h),label,.9f,mask(x,y,w,h))

    @Test fun masksRejectOverlapsInvalidExtentsAndUnorderedNativeData() {
        assertFailsWith<IllegalArgumentException> {ComicMask(intArrayOf(1,0,4,1,3,4),20,20)}
        assertFailsWith<IllegalArgumentException> {ComicMask(intArrayOf(1,19,2),20,20)}
        assertFailsWith<IllegalArgumentException> {ComicMask(intArrayOf(2,0,4,1,0,4),20,20)}
        assertFailsWith<IllegalStateException> {ComicDetection.fromNative(intArrayOf(0,0,10,10,9999,1,0),20,20)}
    }
    @Test fun independentExtentMasksIntersectAndUnionWithoutDoubleCounting() {
        val a=mask(3,4,10,8);val b=mask(7,6,8,4)
        assertEquals(24L,a.intersection(b));assertEquals(24L,b.intersection(a))
        val union=assertNotNull(ComicMask.union(listOf(a,b)))
        assertEquals(88L,union.area);assertTrue(union.contains(14,7));assertFalse(union.contains(15,7))
        assertEquals(a,ComicMask.union(listOf(a,a)))
        assertEquals(24L,assertNotNull(a.clippedTo(b)).area)
    }
    @Test fun primitiveUnionMergesAdjacentRunsButPreservesHolesAndMaximumCoordinates() {
        val first=ComicMask(intArrayOf(0,0,2,0,8,3,2047,2047,1),2048,2048)
        val second=ComicMask(intArrayOf(0,2,3,0,9,4,2047,0,2047),2048,2048)
        val result=assertNotNull(ComicMask.union(listOf(second,first,first)))
        assertContentEquals(intArrayOf(0,0,5,0,8,5,2047,0,2048),result.nativeSpans())
        assertEquals(2058L,result.area)
        assertEquals(OcrRect(0,0,2048,2048),result.bounds)
        assertFalse(result.contains(6,0));assertTrue(result.contains(2047,2047))
        assertFailsWith<IllegalArgumentException> {ComicMask.union(listOf(first,mask(0,0,1,1)))}
    }
    @Test fun primitiveUnionMatchesPixelCoverageAcrossOverlappingSparseMasks() {
        val width=57;val height=61
        val samples=(0..4).map {seed -> BooleanArray(width*height){index ->
            val x=index%width;val y=index/width;(x*37+y*17+seed*23)%97<13
        }}
        val masks=samples.map {sample ->
            val runs=ArrayList<Int>()
            for(y in 0 until height) {
                var x=0
                while(x<width) {
                    if(!sample[y*width+x]){x++;continue}
                    val start=x
                    while(x<width && sample[y*width+x])x++
                    runs+=y;runs+=start;runs+=x-start
                }
            }
            ComicMask(runs.toIntArray(),width,height)
        }
        val merged=assertNotNull(ComicMask.union(masks.reversed()+masks.take(2)))
        var expectedArea=0L
        repeat(width*height) {index ->
            val expected=samples.any {it[index]}
            assertEquals(expected,merged.contains(index%width,index/width),"pixel $index")
            if(expected)expectedArea++
        }
        assertEquals(expectedArea,merged.area)
    }
    @Test fun unionRejectsExcessInputComplexityBeforeBuildingItsSortBuffer() {
        val runs=IntArray(30_000)
        repeat(10_000) {index -> runs[index*3]=index/100;runs[index*3+1]=(index%100)*2;runs[index*3+2]=1}
        val detailed=ComicMask(runs,200,200)
        assertFailsWith<IllegalArgumentException> {ComicMask.union(List(101){detailed})}
    }
    @Test fun hierarchyReadsAllBubblesInRightPanelBeforeLeftPanel() {
        val scene=KoharuPageScene.regions(listOf(
            element(0,0,90,195,3),element(100,0,90,195,3),
            element(110,5,70,65,0),element(110,100,70,65,0),element(10,40,70,65,0),
            element(125,15,30,40,1),element(125,110,30,40,1),element(25,50,30,40,1),
            element(5,170,25,20,4)),200,200)
        assertEquals(3,scene.size) // SFX are retained by detection, not fed to dialogue OCR.
        assertEquals(listOf(100,100,0),scene.map {it.panel?.x})
        assertEquals(listOf(5,100,40),scene.map {it.bubble?.y})
    }
    @Test fun bubbleAssignmentUsesMaskInkInsteadOfRectangleCorners() {
        val bubble=element(10,10,80,80,0)
        // A broad text rectangle crosses the bubble; all detected ink is inside it.
        val text=ComicDetection(OcrRect(0,0,120,120),1,.8f,mask(30,30,20,40))
        val region=KoharuPageScene.regions(listOf(bubble,text),200,200).single()
        assertEquals(bubble.bounds,region.bubble)
        val outside=text.copy(mask=mask(80,30,40,40))
        assertNull(KoharuPageScene.regions(listOf(bubble,outside),200,200).single().bubble)
    }
    @Test fun flatBubbleMeasuresItsColorAndOnlyMasksTextWhileArtworkGetsNoFill() {
        val bubble=element(10,10,80,100,0);val text=element(30,30,20,40,1)
        val region=KoharuPageScene.regions(listOf(bubble,text),200,200).single()
        val pixels=IntArray(40000){0xfff8efdd.toInt()}
        text.mask!!.forEachSpan {y,x,length -> repeat(length){pixels[y*200+x+it]=0xff111111.toInt()}}
        val paint=assertNotNull(KoharuPageScene.preparePaint(region,pixels,200,200).paint)
        assertEquals(0xfff8efdd.toInt(),paint.background);assertEquals(text.mask,paint.mask)
        assertTrue(paint.layout.area<routeArea(bubble.bounds));assertEquals(0xff111111.toInt(),paint.foreground)
        val patterned=IntArray(40000){if(it%200<50)0xff000000.toInt() else -1}
        assertNull(KoharuPageScene.preparePaint(region,patterned,200,200).paint)
    }
    @Test fun cropOffsetMovesMasksPaintAndGeometryTogether() {
        val text=mask(5,5,10,20,100,100)
        val source=OcrRegion(OcrRect(5,5,10,20),.9f,OcrRect(0,0,30,40),text,text,
            paint=MangaPaint(text,OcrRect(3,3,20,30),-1,0xff000000.toInt()))
        val moved=source.translated(20,30,200,200)
        assertEquals(OcrRect(25,35,10,20),moved.bounds)
        assertTrue(assertNotNull(moved.paint).mask.contains(25,35))
        assertFalse(moved.paint.mask.contains(5,5));assertEquals(23,moved.paint.layout.x)
    }
    @Test fun preliminaryOutlinesPrecedeFullSegmentationAndNeuralCleanup() {
        val events=mutableListOf<String>()
        val bubble=element(10,10,80,100,0);val text=element(30,30,20,40,1)
        fun detector(hits: List<ComicDetection>,name: String)=object:ComicRegionDetector {
            override fun detect(pixels: IntArray,width: Int,height: Int): List<ComicDetection> {events+=name;return hits}
            override fun cancel() {};override fun close() {events+="$name-close"}
        }
        val reader=object:MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int)="日本語"
            override fun cancel() {};override fun close() {events+="reader-close"}
        }
        val inpainter=object:MangaPageInpainter {
            override fun inpaint(pixels: IntArray,width: Int,height: Int,mask: ComicMask): MangaPatch {
                events+="neural";return MangaPatch(OcrRect(30,30,20,40),IntArray(800){-1})
            }
            override fun cancel() {};override fun close() {events+="cleanup-close"}
        }
        val page=MangaPageOcr(detector(listOf(bubble,text),"full"),reader,
            detector(listOf(text.copy(mask=null)),"preview"),180000,inpainter)
        val result=page.recognize(IntArray(40000){if(it%2==0)-1 else 0xff000000.toInt()},200,200,object:PageOcrProgress {
            override fun stage(stage: PageOcrStage) {events+=stage.name}
            override fun detected(regions: List<OcrRegion>) {events+="outline"}
        })
        assertEquals(listOf("PREVIEW","preview","outline","SEGMENTATION","full","outline","RECOGNITION","CLEANUP","neural"),events)
        assertNotNull(result.single().region.paint?.patch)
        page.close();assertTrue(events.containsAll(listOf("full-close","preview-close","reader-close","cleanup-close")))
    }
    @Test fun cleanupFailureRetainsRecognizedTextAndPreservesActualError() {
        val source=element(30,30,20,40,1);var retained=emptyList<PageText>()
        val detector=object:ComicRegionDetector {
            override fun detect(pixels: IntArray,width: Int,height: Int)=listOf(source)
            override fun cancel() {};override fun close() {}
        }
        val reader=object:MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int)="recognized"
            override fun cancel() {};override fun close() {}
        }
        val error=IllegalStateException("ORT cleanup failed")
        val cleanup=object:MangaPageInpainter {
            override fun inpaint(pixels: IntArray,width: Int,height: Int,mask: ComicMask): MangaPatch=throw error
            override fun cancel() {};override fun close() {}
        }
        val thrown=runCatching {MangaPageOcr(detector,reader,inpainter=cleanup).recognize(IntArray(40000),200,200,object:PageOcrProgress {
            override fun recognized(text: List<PageText>) {retained=text}
        })}.exceptionOrNull()
        assertSame(error,thrown);assertEquals("recognized",retained.single().text)
        assertFailsWith<IllegalArgumentException>{MangaPatch(OcrRect(0,0,2,2),IntArray(3))}
    }
    @Test fun transcriptsAreAllPublishedBeforeOneUnionCleanupAndEachPatchOnlyCoversItsOwnText() {
        val texts=listOf(element(20,20,10,20,1),element(100,80,10,20,1),element(50,140,10,20,1))
        val events=mutableListOf<String>();val snapshots=mutableListOf<List<PageText>>()
        val detector=object:ComicRegionDetector {
            override fun detect(pixels: IntArray,width: Int,height: Int)=texts
            override fun cancel() {};override fun close() {}
        }
        var reads=0;var cleanups=0
        val reader=object:MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int):String {events+="read";return "text ${++reads}"}
            override fun cancel() {};override fun close() {}
        }
        val cleanup=object:MangaPageInpainter {
            override fun inpaint(pixels: IntArray,width: Int,height: Int,mask: ComicMask): MangaPatch {
                cleanups++;events+="cleanup"
                assertEquals(3,reads);assertEquals(3,snapshots.size)
                assertTrue(snapshots.last().all {it.region.paint==null})
                assertEquals(600L,mask.area)
                texts.forEach { assertEquals(200L,mask.intersection(it.mask!!)) }
                return MangaPatch(OcrRect(0,0,200,200),IntArray(40000){-1})
            }
            override fun cancel() {};override fun close() {}
        }
        val results=MangaPageOcr(detector,reader,inpainter=cleanup).recognize(IntArray(40000),200,200,object:PageOcrProgress {
            override fun recognized(text: List<PageText>) {events+="published";snapshots+=text}
        })
        assertEquals(listOf("read","published","read","published","read","published","cleanup","published"),events)
        assertEquals(1,cleanups);assertEquals(listOf(1,2,3,3),snapshots.map {it.size})
        assertTrue(snapshots[2].all {it.region.paint==null}) // Old snapshots remain immutable.
        results.forEach { recognized ->
            val patch=assertNotNull(recognized.region.paint?.patch)
            val ownMask=assertNotNull(recognized.region.textMask)
            assertEquals(ownMask.bounds,patch.bounds)
            var count=0
            patch.forEachPixel {x,y,_ -> assertTrue(ownMask.contains(x,y));count++}
            assertEquals(200,count)
        }
    }
    @Test fun cleanupClippingRetainsMaskHolesAndRejectsMissingGeneratedPixels() {
        val sparse=ComicMask(intArrayOf(20,20,3,20,26,3,21,21,1),100,100)
        assertEquals(OcrRect(20,20,9,2),sparse.bounds)
        val patch=MangaPatch(OcrRect(0,0,100,100),IntArray(10000){-1}).clippedTo(sparse)
        assertEquals(sparse.bounds,patch.bounds)
        assertEquals(7,patch.copyPixels().count {it.ushr(24)==255})
        assertEquals(0,patch.copyPixels()[4]) // Do not paint the hole between glyphs.
        assertFailsWith<IllegalStateException> {MangaPatch(OcrRect(20,20,3,3),IntArray(9){-1}).clippedTo(sparse)}
        assertFailsWith<IllegalStateException> {MangaPatch(OcrRect(0,0,100,100),IntArray(10000)).clippedTo(sparse)}
    }
    @Test fun uniformBackgroundsPrepareNeuralContextWithoutMutatingOriginal() {
        val bubble=element(10,10,70,100,0)
        val uniform=element(30,30,15,40,1)
        val complex=element(120,30,15,40,1)
        val original=IntArray(40000){-1}
        uniform.mask!!.forEachSpan {y,x,length -> original.fill(0xff111111.toInt(),y*200+x,y*200+x+length)}
        val before=original.copyOf()
        val detector=object:ComicRegionDetector {
            override fun detect(pixels:IntArray,width:Int,height:Int)=listOf(bubble,uniform,complex)
            override fun cancel() {};override fun close() {}
        }
        val reader=object:MangaRegionReader {
            override fun read(pixels:IntArray,width:Int,height:Int)="日本語"
            override fun cancel() {};override fun close() {}
        }
        var called=false
        val cleanup=object:MangaPageInpainter {
            override fun inpaint(pixels:IntArray,width:Int,height:Int,mask:ComicMask):MangaPatch {
                called=true;assertNotSame(original,pixels)
                assertEquals(-1,pixels[40*200+35])
                assertFalse(mask.contains(35,40))
                return MangaPatch(complex.mask!!.bounds,IntArray(15*40){-1})
            }
            override fun cancel() {};override fun close() {}
        }
        MangaPageOcr(detector,reader,inpainter=cleanup).recognize(original,200,200,object:PageOcrProgress{})
        assertTrue(called);assertContentEquals(before,original)
    }
    @Test fun obsoletePageDoesNotStartCleanupAfterTextPublication() {
        var current=true;var cleaned=false
        val detector=object:ComicRegionDetector {
            override fun detect(pixels: IntArray,width: Int,height: Int)=listOf(element(20,20,10,20,1))
            override fun cancel() {};override fun close() {}
        }
        val reader=object:MangaRegionReader {
            override fun read(pixels: IntArray,width: Int,height: Int)="recognized"
            override fun cancel() {};override fun close() {}
        }
        val cleanup=object:MangaPageInpainter {
            override fun inpaint(pixels: IntArray,width: Int,height: Int,mask: ComicMask): MangaPatch {cleaned=true;error("stale cleanup")}
            override fun cancel() {};override fun close() {}
        }
        MangaPageOcr(detector,reader,inpainter=cleanup).recognize(IntArray(40000),200,200,object:PageOcrProgress {
            override fun isCurrent()=current
            override fun recognized(text: List<PageText>) {current=false}
        })
        assertFalse(cleaned)
    }
    @Test fun placeholderOnlyMangaTextUsesUpstreamEllipsisNormalization() {
        assertEquals("…",normalizeMangaText("☐ ☐ ☐"))
        assertEquals("…",normalizeMangaText("□\n□"))
        assertEquals("☐",normalizeMangaText(" ☐ "))
        assertEquals("待って…",normalizeMangaText(" 待って… "))
        assertEquals("□へ",normalizeMangaText("□へ"))
    }
    private fun routeArea(rect: OcrRect)=rect.area
}
