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
        assertEquals(listOf("PREVIEW","preview","outline","SEGMENTATION","full","outline","RECOGNITION","CLEANUP","neural","RECOGNITION"),events)
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
    private fun routeArea(rect: OcrRect)=rect.area
}
