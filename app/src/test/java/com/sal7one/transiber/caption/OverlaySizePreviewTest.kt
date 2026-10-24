package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class OverlaySizePreviewTest {
    @Test fun staleEmissionsCannotUndoMinimalHeightOrBringBackAHiddenHeader() {
        val preview = OverlaySizePreview()
        val dragged = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 96, bubbleHeightDp = 260, widthPercent = 90)
        preview.preview(dragged)
        val stored = dragged.copy(minimalHeader = false, minimalHeightDp = 64, widthPercent = 60, theme = CaptionTheme.LIGHT)
        val current = preview.applyTo(stored)
        assertEquals(96, current.minimalHeightDp); assertEquals(260, current.bubbleHeightDp)
        assertEquals(90, current.widthPercent); assertFalse(current.minimalHeader)
        assertEquals(CaptionTheme.LIGHT, current.theme)
        val first = preview.beginCommit(dragged)
        val newer = dragged.copy(minimalHeightDp = 120)
        preview.preview(newer); preview.finishCommit(first)
        assertEquals(120, preview.applyTo(stored).minimalHeightDp)
        val latest = preview.beginCommit(newer); preview.finishCommit(latest)
        assertEquals(64, preview.applyTo(stored).minimalHeightDp)
    }

    @Test fun resetDuringMinimalResizeResetsBothSizesAndRotationInvalidatesTheOldDraft() {
        val preview = OverlaySizePreview()
        val dragged = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 200, bubbleHeightDp = 260)
        preview.preview(dragged)
        val reset = dragged.resetOverlayPresentation(OverlayOrientation.PORTRAIT)
        val resetCommit = preview.beginCommit(reset)
        assertEquals(64, preview.applyTo(dragged).minimalHeightDp)
        assertNull(preview.applyTo(dragged).bubbleHeightDp)
        preview.cancel()
        val landscape = dragged.copy(minimalHeightDp = 88, bubbleHeightDp = 180)
        preview.preview(landscape)
        preview.finishCommit(resetCommit)
        assertEquals(88, preview.applyTo(reset).minimalHeightDp)
    }

    @Test fun staleStorageCannotUndoAResizeButOtherSettingsStillApply() {
        val preview = OverlaySizePreview()
        preview.preview(CaptionOverlayConfig(widthPercent = 80, bubbleHeightDp = 240))
        val stored = CaptionOverlayConfig(widthPercent = 60, bubbleHeightDp = 160,
            theme = CaptionTheme.LIGHT, speakCaptions = true, speakerVolume = 30)
        val next = preview.applyTo(stored)
        assertEquals(80, next.widthPercent)
        assertEquals(240, next.bubbleHeightDp)
        assertEquals(CaptionTheme.LIGHT, next.theme)
        assertTrue(next.speakCaptions)
        assertEquals(30, next.speakerVolume)
    }

    @Test fun anEarlierCommitCannotClearANewerGesture() {
        val preview = OverlaySizePreview()
        val first = CaptionOverlayConfig(bubbleHeightDp = 200)
        preview.preview(first)
        val firstCommit = preview.beginCommit(first)
        val second = first.copy(bubbleHeightDp = 260)
        preview.preview(second)
        preview.finishCommit(firstCommit)
        assertEquals(260, preview.applyTo(first).bubbleHeightDp)

        val finalCommit = preview.beginCommit(second)
        preview.finishCommit(finalCommit)
        assertEquals(180, preview.applyTo(second.copy(bubbleHeightDp = 180)).bubbleHeightDp)
    }

    @Test fun resetDuringAResizeCommitsTheResetSize() {
        val preview = OverlaySizePreview()
        val dragged = CaptionOverlayConfig(widthPercent = 100, bubbleHeightDp = 144)
        preview.preview(dragged)
        val reset = dragged.resetOverlayPresentation(OverlayOrientation.LANDSCAPE)
        val commit = preview.beginCommit(reset)
        assertEquals(reset.widthPercent, preview.applyTo(dragged).widthPercent)
        assertNull(preview.applyTo(dragged).bubbleHeightDp)
        preview.finishCommit(commit)
        assertEquals(reset, preview.applyTo(reset))
    }

    @Test fun rotationOrCloseDiscardsTheDraftAndInvalidatesPendingCommits() {
        val preview = OverlaySizePreview()
        val dragged = CaptionOverlayConfig(widthPercent = 95, bubbleHeightDp = 500)
        preview.preview(dragged)
        val token = preview.beginCommit(dragged)
        preview.cancel()
        val landscape = CaptionOverlayConfig(widthPercent = 40, bubbleHeightDp = 160)
        assertEquals(landscape, preview.applyTo(landscape))
        preview.preview(landscape.copy(bubbleHeightDp = 200))
        preview.finishCommit(token)
        assertEquals(200, preview.applyTo(landscape).bubbleHeightDp)
    }
}
