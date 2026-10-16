package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class OverlayRemoteMovementTest {
    private val viewport = OverlayViewport(8, 8, 1912, 1072)
    private fun placed(config: CaptionOverlayConfig) = placeOverlay(viewport, viewport.width * config.widthPercent / 100,
        overlayHeightPx(viewport.height, 1f, config), config.anchor, config.xOffsetPx, config.yOffsetPx)

    @Test fun remoteDirectionsMatchScreenDirectionsForEveryAnchor() {
        for (anchor in CaptionAnchor.entries) {
            val config = CaptionOverlayConfig(anchor = anchor, widthPercent = 50, bubbleHeightDp = 200)
            val start = placed(config)
            val up = config.nudgeOverlay(viewport, 1f, -24, -24)
            assertEquals(start.x - 24, placed(up).x)
            assertEquals(maxOf(viewport.top, start.y - 24), placed(up).y)
            val down = config.nudgeOverlay(viewport, 1f, 24, 24)
            assertEquals(start.x + 24, placed(down).x)
            assertEquals(minOf(viewport.bottom - start.height, start.y + 24), placed(down).y)
        }
    }

    @Test fun repeatedEdgePressesDoNotAccumulateInvisibleMovement() {
        var config = CaptionOverlayConfig(widthPercent = 50, bubbleHeightDp = 200)
        repeat(500) { config = config.nudgeOverlay(viewport, 1f, -24, 24) }
        assertEquals(viewport.left, placed(config).x)
        assertEquals(viewport.bottom - 200, placed(config).y)
        val reversed = config.nudgeOverlay(viewport, 1f, 24, -24)
        assertEquals(viewport.left + 24, placed(reversed).x)
        assertEquals(viewport.bottom - 224, placed(reversed).y)
    }

    @Test fun changingSizeOrAnchorAfterMovementStaysWithinDisplay() {
        for (anchor in CaptionAnchor.entries) {
            val config = CaptionOverlayConfig(anchor = anchor, widthPercent = 100, bubbleHeightDp = 600,
                xOffsetPx = Int.MAX_VALUE, yOffsetPx = Int.MIN_VALUE)
                .nudgeOverlay(viewport, 1f, Int.MAX_VALUE, Int.MIN_VALUE)
            val actual = placed(config)
            assertTrue(actual.x >= viewport.left)
            assertTrue(actual.x + actual.width <= viewport.right)
            assertTrue(actual.y >= viewport.top)
            assertTrue(actual.y + actual.height <= viewport.bottom)
            assertEquals(0, config.xOffsetPx)
        }
    }

    @Test fun movementLeavesSpeechAndAppearanceChoicesAlone() {
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.CLOUD, paused = true,
            speakCaptions = true, showSettings = true, theme = CaptionTheme.HIGH_CONTRAST, widthPercent = 75)
        val moved = config.nudgeOverlay(viewport, 1f, -24, -24)
        assertEquals(config, moved.copy(xOffsetPx = config.xOffsetPx, yOffsetPx = config.yOffsetPx))
    }
}
