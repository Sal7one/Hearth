package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class TvCaptionPresentationTest {
    private val config = CaptionOverlayConfig(tvSubtitleMode = true, mode = CaptionMode.TRANSLATE)
    private val content = CaptionReadingSnapshot(
        listOf(CaptionLine("Earlier", "سابق"), CaptionLine("Current", "حالي")), "", null)

    @Test fun subtitlesReplaceOldUtterancesAndHonorOriginalTranslationAndBoth() {
        assertEquals("Current\nحالي", tvCaptionText(content, config))
        assertEquals("Current", tvCaptionText(content, config.copy(display = CaptionDisplay.ORIGINAL)))
        assertEquals("حالي", tvCaptionText(content, config.copy(display = CaptionDisplay.TRANSLATED)))
        assertEquals("Partial\nجارٍ", tvCaptionText(content.copy(partial = "Partial", partialTranslation = "جارٍ"), config))
        assertEquals("حالي", tvCaptionText(content.copy(partial = "Partial", partialTranslation = ""),
            config.copy(display = CaptionDisplay.TRANSLATED)))
        assertEquals("", tvCaptionText(CaptionReadingSnapshot(emptyList(), "", null), config))
    }

    @Test fun translationOnlyNeverShowsPendingSourceAndAcceptsTranslationOnlyPartials() {
        val translated = config.copy(display = CaptionDisplay.TRANSLATED)
        val pending = CaptionReadingSnapshot(listOf(CaptionLine("Do not show original")), "Nor source interim", null)
        assertEquals("", tvCaptionText(pending, translated))
        assertEquals("", tvCaptionText(pending, translated.copy(tvSubtitleMode = false)))
        assertEquals("ترجمة", tvCaptionText(pending.copy(partial = "", partialTranslation = "ترجمة"), translated))
        val direct = CaptionReadingSnapshot(listOf(CaptionLine("Target output", primaryRole = CaptionTextRole.TARGET)), "", null)
        assertEquals("Target output", tvCaptionText(direct, translated))
        assertEquals("Current", tvCaptionText(content, translated.copy(mode = CaptionMode.CAPTIONS)))
    }

    @Test fun textPanelRetainsSelectedHistoryWithoutStatusOrControlText() {
        assertEquals("Earlier\nسابق\nCurrent\nحالي", tvCaptionText(content, config.copy(tvSubtitleMode = false)))
        assertEquals("Current\nحالي", tvCaptionText(content, config.copy(tvSubtitleMode = false, historyLines = 0)))
    }

    @Test fun compactTvHeightSurvivesPersistenceWhilePhoneKeepsItsMinimum() {
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(prefs, config.copy(bubbleHeightDp = 32, tvSubtitleMode = false), OverlayOrientation.LANDSCAPE)
        val tv = CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE, television = true)
        assertEquals(32, tv.bubbleHeightDp)
        assertFalse(tv.tvSubtitleMode)
        assertEquals(64, overlayHeightPx(1080, 2f, tv, television = true))
        val phone = CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE)
        assertEquals(144, phone.bubbleHeightDp)
        assertEquals(288, overlayHeightPx(1080, 2f, phone))
    }

    @Test fun tvDefaultsAndResetAreCompactWithoutOverridingPhoneGeometry() {
        val stored = config.copy(tvSubtitleMode = false, bubbleHeightDp = 240, engine = CaptionEngineChoice.CLOUD)
        val reset = stored.resetOverlayPresentation(OverlayOrientation.LANDSCAPE, television = true)
        assertTrue(reset.tvSubtitleMode)
        assertNull(reset.bubbleHeightDp)
        assertEquals(128, overlayHeightPx(1080, 2f, reset, television = true))
        assertEquals(CaptionEngineChoice.CLOUD, reset.engine)
        assertEquals(CaptionAnchor.BOTTOM, reset.anchor)
        assertFalse(stored.resetOverlayPresentation(OverlayOrientation.PORTRAIT).tvSubtitleMode)
        assertEquals(60, overlayHeightPx(60, 3f, reset.copy(bubbleHeightDp = 32), television = true))
    }

    @Test fun remoteMovementUsesTheActualCompactWindowInEveryAnchor() {
        val viewport = OverlayViewport(8, 8, 1912, 1072)
        for (anchor in CaptionAnchor.entries) {
            val initial = config.copy(anchor = anchor, bubbleHeightDp = 32)
            val moved = initial.nudgeOverlay(viewport, 1f, 0, Int.MAX_VALUE, television = true)
            val placement = placeOverlay(viewport, viewport.width * moved.widthPercent / 100, 32,
                anchor, moved.xOffsetPx, moved.yOffsetPx)
            assertEquals(viewport.bottom, placement.y + placement.height)
        }
    }
}
