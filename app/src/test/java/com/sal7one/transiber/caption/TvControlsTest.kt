package com.sal7one.transiber.caption

import android.view.KeyEvent
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test

class TvControlsTest {
    @Test fun overlayStaysNonFocusableExceptTvSettingsSheet() {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        assertEquals(base, TvControls.overlayWindowFlags(tapThrough = false, settingsSheetOpen = false, television = true))
        assertEquals(base, TvControls.overlayWindowFlags(tapThrough = false, settingsSheetOpen = false, television = false))
        assertEquals(base, TvControls.overlayWindowFlags(tapThrough = false, settingsSheetOpen = true, television = false))
        // The one focusable moment: TV + settings sheet open.
        val focused = TvControls.overlayWindowFlags(tapThrough = false, settingsSheetOpen = true, television = true)
        assertEquals(0, focused and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, focused and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        // Tap-through keeps its not-touchable bit in every combination.
        TvControls.overlayWindowFlags(tapThrough = true, settingsSheetOpen = true, television = true).let {
            assertEquals(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, it and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

    @Test fun remoteMediaKeysMapToServiceActions() {
        assertEquals(CaptionCaptureService.ACTION_PAUSE, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CaptionCaptureService.ACTION_PAUSE, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CaptionCaptureService.ACTION_STOP, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_STOP))
        assertNull(TvControls.remoteKeyToAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(TvControls.remoteKeyToAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(TvControls.remoteKeyToAction(KeyEvent.KEYCODE_BACK))
    }
}
