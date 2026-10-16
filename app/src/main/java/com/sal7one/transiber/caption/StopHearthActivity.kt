package com.sal7one.transiber.caption

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.Context
import android.os.Bundle
import com.sal7one.transiber.reading.ReadingOverlayService

/** Emergency TV launcher action: no overlay interaction, capture consent or engine startup. */
class StopHearthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopAndCloseHearth()
    }
}

internal fun Activity.stopAndCloseHearth() {
    stopHearthSessions(this)
    getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
    finishAndRemoveTask()
}

/** The TV dashboard stops capture without dismissing its own Start/Stop controls. */
internal fun stopHearthSessions(context: Context) {
    // stopService is also safe when idle: never create an engine just to send it STOP.
    // Both services release capture, overlay windows and speech in onDestroy.
    context.stopService(Intent(context, CaptionCaptureService::class.java))
    context.stopService(Intent(context, ReadingOverlayService::class.java))
}
