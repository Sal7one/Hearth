package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class OverlayHeightTest {
    @Test fun upgradeKeepsLegacySizeAndExplicitHeightIsIndependentOfOldPercent() {
        val old = CaptionOverlayConfig(maxHeightPercent = 55)
        assertEquals(640, overlayHeightPx(1800, 2f, old))
        assertEquals(440, overlayHeightPx(800, 2f, old))
        assertEquals(320, overlayHeightPx(1800, 2f, old.copy(bubbleHeightDp = 160)))
        assertEquals(1000, overlayHeightPx(1800, 2f, old.copy(bubbleHeightDp = 500)))
    }
    @Test fun rotationAndTinyWindowsKeepEntireBubbleInsideViewport() {
        val config = CaptionOverlayConfig(bubbleHeightDp = 600)
        val height = overlayHeightPx(300, 3f, config)
        assertEquals(300, height)
        val placement = placeOverlay(OverlayViewport(0, 0, 800, 300), 700, height, CaptionAnchor.BOTTOM, 0, -999)
        assertEquals(0, placement.y)
        assertEquals(1, overlayHeightPx(1, 3f, config))
    }
    @Test fun savedHeightRoundTripsWithoutChangingModeOrLegacyPreferences() {
        val prefs = mutablePreferencesOf()
        assertNull(CaptionConfigStore.readFrom(prefs).bubbleHeightDp)
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(bubbleHeightDp = 160, mode = CaptionMode.TRANSLATE))
        val restored = CaptionConfigStore.readFrom(prefs)
        assertEquals(160, restored.bubbleHeightDp)
        assertEquals(CaptionMode.TRANSLATE, restored.mode)
    }
}
