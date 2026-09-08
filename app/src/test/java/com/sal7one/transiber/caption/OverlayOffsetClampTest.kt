package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class OverlayOffsetClampTest {
    @Test fun allAnchorsKeepEntireWindowInsideBarsAndCutout() {
        val viewport = OverlayViewport(80, 110, 1400, 2900)
        for (anchor in CaptionAnchor.entries) for (x in listOf(Int.MIN_VALUE, -500, 0, 500, Int.MAX_VALUE)) {
            for (y in listOf(Int.MIN_VALUE, -3000, 0, 3000, Int.MAX_VALUE)) {
                val p = placeOverlay(viewport, 1200, 700, anchor, x, y)
                assertTrue(p.x >= viewport.left && p.y >= viewport.top)
                assertTrue(p.x + p.width <= viewport.right && p.y + p.height <= viewport.bottom)
            }
        }
    }
    @Test fun stalePortraitPositionFitsLandscapeAndLargeSettingsPanel() {
        val p = placeOverlay(OverlayViewport(100, 60, 2600, 1000), 2800, 1600, CaptionAnchor.BOTTOM, 900, 1293)
        assertEquals(100, p.x); assertEquals(60, p.y)
        assertEquals(2500, p.width); assertEquals(940, p.height)
    }
    @Test fun bottomDragUsesLiftButCoordinatesAreTopLeft() {
        val vp = OverlayViewport(10, 20, 1010, 2020)
        val p = placeOverlay(vp, 800, 300, CaptionAnchor.BOTTOM, 20, 200)
        assertEquals(130, p.x); assertEquals(1520, p.y)
    }
    @Test fun contentGrowthIsReclampedWithoutLosingHandle() {
        val vp = OverlayViewport(10, 20, 1010, 2020)
        val short = placeOverlay(vp, 800, 100, CaptionAnchor.TOP, 0, 1900)
        val tall = placeOverlay(vp, 800, 600, CaptionAnchor.TOP, 0, 1900)
        assertEquals(1920, short.y); assertEquals(1420, tall.y)
    }
}
