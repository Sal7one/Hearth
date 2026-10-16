package com.sal7one.transiber.caption

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
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
    // stopService is also safe when idle: never create an engine just to send it STOP.
    // Both services release capture, overlay windows and speech in onDestroy.
    stopService(Intent(this, CaptionCaptureService::class.java))
    stopService(Intent(this, ReadingOverlayService::class.java))
    getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
    finishAndRemoveTask()
}
