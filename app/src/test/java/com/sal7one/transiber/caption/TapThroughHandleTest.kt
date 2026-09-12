package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class TapThroughHandleTest {
    @Test fun handlePrefersOutsideCaptionText() {
        val viewport = OverlayViewport(10, 20, 1010, 2020)
        val bottom = placeTapThroughHandle(viewport, OverlayPlacement(50, 1600, 900, 400), 112, 8)
        assertEquals(1480, bottom.y)
        assertEquals(838, bottom.x)
        val top = placeTapThroughHandle(viewport, OverlayPlacement(50, 20, 900, 400), 112, 8)
        assertEquals(428, top.y)
    }
    @Test fun edgeDragsRotationAndFullScreenAlwaysLeaveHandleReachable() {
        for (viewport in listOf(OverlayViewport(30, 100, 1410, 2800), OverlayViewport(100, 30, 2800, 1000), OverlayViewport(0, 0, 20, 10))) {
            for (anchor in CaptionAnchor.entries) for (offset in listOf(-100000, 0, 100000)) {
                val bubble = placeOverlay(viewport, viewport.width, viewport.height, anchor, offset, offset)
                val handle = placeTapThroughHandle(viewport, bubble, 168, 12)
                assertTrue(handle.x >= viewport.left && handle.y >= viewport.top)
                assertTrue(handle.x + handle.width <= viewport.right && handle.y + handle.height <= viewport.bottom)
                assertTrue(handle.width > 0 && handle.height > 0)
            }
        }
    }
}
