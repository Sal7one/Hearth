package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.PipelineSnapshot
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlaySettings
import com.sal7one.hearth.desktop.ui.screens.overlay.overlayState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopOverlayPendingPreferenceTest {
    @Test fun actualFloatingOverlayMapperHonorsPendingOriginalAndCompletedTranslation() {
        val pending = PipelineSnapshot("test", segments = listOf(CaptionSegment("line", "Hello")))
        fun mapped(snapshot: PipelineSnapshot, showPending: Boolean, translate: Boolean = true) = overlayState(
            snapshot, translate, "ar", OverlaySettings(), showOriginalWhilePending = showPending)
        assertNull(mapped(pending, false).caption)
        assertEquals("Hello", mapped(pending, true).caption?.primary)
        assertEquals("Hello", mapped(pending, false, translate = false).caption?.primary)
        val translated = pending.copy(segments = listOf(CaptionSegment("line", "Hello", translation = "مرحبا")))
        assertEquals("مرحبا", mapped(translated, false).caption?.primary)
        assertEquals("مرحبا", mapped(translated, true).caption?.primary)
    }
}
