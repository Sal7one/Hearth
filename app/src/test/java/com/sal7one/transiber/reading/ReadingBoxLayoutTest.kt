package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import org.junit.Assert.*
import org.junit.Test

class ReadingBoxLayoutTest {
    @Test fun tinyEdgeBoxGrowsInsideViewportWithoutChangingItsSource() {
        val source=PixelCrop(290,190,300,200)
        val placed=ReadingBoxLayout.place(source,120,60,300,200,emptyList())!!
        assertEquals(PixelCrop(180,140,300,200),placed)
        assertEquals(PixelCrop(290,190,300,200),source)
    }
    @Test fun neighbouringExpandedParagraphsPreferFreeSpaceOverEachOther() {
        val first=ReadingBoxLayout.place(PixelCrop(10,10,110,20),120,40,300,200,emptyList())!!
        val second=ReadingBoxLayout.place(PixelCrop(10,25,110,35),120,40,300,200,listOf(first))!!
        assertTrue(second.top>=first.bottom || second.left>=first.right || second.right<=first.left)
        assertTrue(second.right<=300 && second.bottom<=200)
    }
    @Test fun impossibleDenseLayoutChoosesBoundedPlacementAndNeverNegativeDimensions() {
        val placed=ReadingBoxLayout.place(PixelCrop(0,0,20,20),500,500,100,80,listOf(PixelCrop(0,0,100,80)))!!
        assertEquals(PixelCrop(0,0,100,80),placed)
        assertNull(ReadingBoxLayout.place(PixelCrop(0,0,20,20),100,100,0,80,emptyList()))
        assertNull(ReadingBoxLayout.place(PixelCrop(1,1,1,2),100,100,100,80,emptyList()))
    }
    @Test fun requestedWidthAndHeightRemainIndependent() {
        assertEquals(100,ReadingBoxLayout.scaled(200,50))
        assertEquals(600,ReadingBoxLayout.scaled(200,300))
    }
}
