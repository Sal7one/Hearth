package com.sal7one.transiber.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

internal object DownloadRunner {
    val running get() = DownloadService.running
    fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
    fun cancel(id: Long) { DownloadService.jobs[id]?.cancel() }
}
