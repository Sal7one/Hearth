package com.sal7one.transiber.downloads

import android.content.Context

internal object DownloadRunner {
    const val running = false
    fun start(context: Context): Unit = error("Downloads are unavailable in the offline build")
    fun cancel(id: Long) {}
}
