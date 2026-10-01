package com.sal7one.transiber.caption

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager

/**
 * Android TV / large-screen support. Remotes have no touch and usually only a
 * D-pad plus media keys, so control flows through three paths: the overlay's
 * own window becomes focusable while its settings sheet is open (D-pad),
 * the MediaSession/media notification row carries Pause / Silence / Stop, and
 * media keys work wherever Hearth is the active session.
 */
object TvControls {
    fun isTvDevice(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Pure window-flag decision (unit-testable): overlays stay non-focusable so
     * they never steal keys from the underlying app, except on TV while the
     * settings sheet is open — that is the one moment D-pad focus is wanted.
     */
    fun overlayWindowFlags(tapThrough: Boolean, settingsSheetOpen: Boolean, television: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (tapThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (television && settingsSheetOpen) flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        return flags
    }

    /** Remote media key → service action, shared by the activity and the media session. */
    fun remoteKeyToAction(keyCode: Int): String? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> CaptionCaptureService.ACTION_PAUSE
        android.view.KeyEvent.KEYCODE_MEDIA_STOP -> CaptionCaptureService.ACTION_STOP
        else -> null
    }
}
