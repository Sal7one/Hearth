package com.sal7one.transiber.caption

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager

/** TV overlays contain text only; remote actions live in the controls activity and media session. */
object TvControls {
    @Suppress("DEPRECATION")
    fun isTvDevice(context: Context): Boolean = isTvEnvironment(
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType,
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION),
    )

    /** Some TV boxes advertise their TV launcher without setting UiModeManager correctly. */
    internal fun isTvEnvironment(mode: Int?, leanback: Boolean, television: Boolean): Boolean =
        mode == Configuration.UI_MODE_TYPE_TELEVISION || leanback || television

    /** TV text must never consume remote keys or touches from the video app. */
    @Suppress("UNUSED_PARAMETER")
    fun overlayWindowFlags(tapThrough: Boolean, settingsSheetOpen: Boolean, television: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (tapThrough || television) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /** Remote media key → service action, shared by the activity and the media session. */
    fun remoteKeyToAction(keyCode: Int): String? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> CaptionCaptureService.ACTION_PAUSE
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> CaptionCaptureService.ACTION_RESUME
        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> CaptionCaptureService.ACTION_PAUSE_ONLY
        android.view.KeyEvent.KEYCODE_MEDIA_STOP -> CaptionCaptureService.ACTION_STOP
        else -> null
    }

    internal fun pausedForAction(action: String, current: Boolean): Boolean = when (action) {
        CaptionCaptureService.ACTION_RESUME -> false
        CaptionCaptureService.ACTION_PAUSE_ONLY -> true
        CaptionCaptureService.ACTION_PAUSE -> !current
        else -> current
    }
}
