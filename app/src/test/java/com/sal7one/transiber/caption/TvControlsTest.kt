package com.sal7one.transiber.caption

import android.view.KeyEvent
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test

class TvControlsTest {
    @Test fun tvModeWorksForTvLaunchersAndBoxesWithoutUiModeManager() {
        assertTrue(TvControls.isTvEnvironment(android.content.res.Configuration.UI_MODE_TYPE_TELEVISION, false, false))
        assertTrue(TvControls.isTvEnvironment(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, true, false))
        assertTrue(TvControls.isTvEnvironment(null, false, true))
        assertFalse(TvControls.isTvEnvironment(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, false, false))
        assertFalse(TvControls.isTvEnvironment(null, false, false))
    }

    @Test fun tvTextNeverTakesFocusOrTouchesEvenWithLegacySettingsState() {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        for (settings in listOf(false, true)) for (tapThrough in listOf(false, true)) {
            assertEquals(base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                TvControls.overlayWindowFlags(tapThrough, settings, television = true))
        }
        assertEquals(base, TvControls.overlayWindowFlags(false, false, false))
        assertEquals(base, TvControls.overlayWindowFlags(false, true, false))
        assertEquals(base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, TvControls.overlayWindowFlags(true, false, false))
    }

    @Test fun remoteMediaKeysMapToServiceActions() {
        assertEquals(CaptionCaptureService.ACTION_PAUSE, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CaptionCaptureService.ACTION_RESUME, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CaptionCaptureService.ACTION_PAUSE_ONLY, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CaptionCaptureService.ACTION_STOP, TvControls.remoteKeyToAction(KeyEvent.KEYCODE_MEDIA_STOP))
        assertNull(TvControls.remoteKeyToAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(TvControls.remoteKeyToAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(TvControls.remoteKeyToAction(KeyEvent.KEYCODE_BACK))
    }

    @Test fun repeatedPlayAndPauseCommandsAreIdempotentWhileToggleStillToggles() {
        for (paused in listOf(false, true)) {
            assertFalse(TvControls.pausedForAction(CaptionCaptureService.ACTION_RESUME, paused))
            assertTrue(TvControls.pausedForAction(CaptionCaptureService.ACTION_PAUSE_ONLY, paused))
            assertEquals(!paused, TvControls.pausedForAction(CaptionCaptureService.ACTION_PAUSE, paused))
        }
    }
}
