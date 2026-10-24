package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class MinimalCaptionOverlayGeometryTest {
    @Test fun idleAndTapThroughUseOnlyTheSavedCompactReadingHeight() {
        val config = CaptionOverlayConfig(minimalHeader = true, bubbleHeightDp = 300)
        assertEquals(128, overlayHeightPx(1800, 2f, config))
        assertEquals(128, overlayHeightPx(1800, 2f, config.copy(tapThrough = true, minimalHeaderExpanded = true)))
        assertEquals(192, overlayHeightPx(1800, 3f, config.copy(minimalHeightDp = 64)))
        assertEquals(96, overlayHeightPx(1800, 2f, config.copy(minimalHeightDp = 10)))
    }

    @Test fun deliberateExpansionAddsTemporaryChromeAndCollapseRestoresTheBaseHeight() {
        val config = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 80, bubbleHeightDp = 260)
        val expanded = config.copy(minimalHeaderExpanded = true)
        assertEquals(352, overlayHeightPx(1800, 2f, expanded))
        assertEquals(160, overlayHeightPx(1800, 2f, expanded.copy(minimalHeaderExpanded = false)))
        assertEquals(80, expanded.minimalHeightDp); assertEquals(260, expanded.bubbleHeightDp)
        assertEquals(288, overlayHeightPx(1800, 2f, expanded.copy(minimalHeightDp = 48)))
    }

    @Test fun settingsTemporarilyUseTheNormalViewportWithAReachableMinimum() {
        val compact = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 64, bubbleHeightDp = 160,
            showSettings = true, minimalHeaderExpanded = true)
        assertEquals(480, overlayHeightPx(1800, 2f, compact))
        assertEquals(560, overlayHeightPx(1800, 2f, compact.copy(bubbleHeightDp = 280)))
        assertEquals(480, overlayHeightPx(1800, 2f, compact.copy(tapThrough = true)))
        assertEquals(128, overlayHeightPx(1800, 2f, compact.copy(showSettings = false, minimalHeaderExpanded = false)))
        assertEquals(64, compact.minimalHeightDp); assertEquals(160, compact.bubbleHeightDp)
    }

    @Test fun everyMinimalWindowStateStaysInsideSmallOrRotatedViewports() {
        val config = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 600, bubbleHeightDp = 600)
        for (state in listOf(config, config.copy(minimalHeaderExpanded = true), config.copy(showSettings = true))) {
            val height = overlayHeightPx(300, 3f, state)
            assertEquals(300, height)
            assertEquals(1, overlayHeightPx(1, 3f, state))
            assertEquals(1, overlayHeightPx(0, 3f, state))
            val placement = placeOverlay(OverlayViewport(8, 12, 792, 312), 700, height, CaptionAnchor.BOTTOM, 0, -999)
            assertEquals(12, placement.y); assertEquals(312, placement.y + placement.height)
        }
    }

    @Test fun ordinaryPhoneAndTelevisionGeometryIgnoreCompactSettings() {
        val standard = CaptionOverlayConfig(bubbleHeightDp = 160, showSettings = true)
        assertEquals(320, overlayHeightPx(1800, 2f, standard))
        assertEquals(320, overlayHeightPx(1800, 2f, standard.copy(minimalHeightDp = 500, minimalHeaderExpanded = true)))
        for (expanded in listOf(false, true)) for (settings in listOf(false, true)) {
            val tv = standard.copy(minimalHeader = true, minimalHeightDp = 500, minimalHeaderExpanded = expanded, showSettings = settings)
            assertEquals(320, overlayHeightPx(1800, 2f, tv, television = true))
            assertEquals(64, overlayHeightPx(1800, 2f, tv.copy(bubbleHeightDp = 32), television = true))
        }
    }

    @Test fun editingTheActiveHeightNeverStoresTemporaryChromeOrOverwritesTheOtherMode() {
        val config = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 64, bubbleHeightDp = 260,
            minimalHeaderExpanded = true, showSettings = true)
        val resized = config.withActiveOverlayHeightDp(110)
        assertEquals(110, resized.minimalHeightDp); assertEquals(260, resized.bubbleHeightDp)
        assertTrue(resized.minimalHeaderExpanded && resized.showSettings)
        val standard = resized.copy(minimalHeader = false).withActiveOverlayHeightDp(200)
        assertEquals(110, standard.minimalHeightDp); assertEquals(200, standard.bubbleHeightDp)
        val tv = config.withActiveOverlayHeightDp(32, television = true)
        assertEquals(64, tv.minimalHeightDp); assertEquals(32, tv.bubbleHeightDp)
        assertEquals(48, config.withActiveOverlayHeightDp(-1).minimalHeightDp)
        assertEquals(600, config.withActiveOverlayHeightDp(Int.MAX_VALUE).minimalHeightDp)
    }
}
