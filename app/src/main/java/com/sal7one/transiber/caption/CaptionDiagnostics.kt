package com.sal7one.transiber.caption

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Small local startup record. No audio, transcript, URL or API key is recorded. */
internal object CaptionDiagnostics {
 private fun prefs(context: Context) = context.getSharedPreferences("caption_startup_diagnostics", Context.MODE_PRIVATE)
 fun record(context: Context, stage: String) {
  val memory = ActivityManager.MemoryInfo()
  context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
  check(prefs(context).edit().putString("stage", stage).putLong("time", System.currentTimeMillis())
   .putLong("available", memory.availMem).putLong("total", memory.totalMem).commit()) { "Cannot save local speech startup diagnostics" }
 }
 /** Preserve Android's trace bytes verbatim: native tombstones may be protobuf, not text. */
 fun exportTrace(context: Context, destination: android.net.Uri) {
  check(Build.VERSION.SDK_INT >= 30) { "Android exit traces require Android 11+" }
  val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
  for (exit in exits.sortedByDescending { it.timestamp }) {
   val trace = exit.traceInputStream ?: continue
   trace.use { input ->
    val output = context.contentResolver.openOutputStream(destination) ?: error("Cannot open trace destination")
    output.use { input.copyTo(it) }
   }
   return
  }
  error("Android has no retained crash trace for this app. Copy the startup report instead.")
 }
 fun report(context: Context): String = buildString {
  appendLine("Real time transiber ${com.sal7one.transiber.BuildConfig.VERSION_NAME}")
  appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
  val stored = prefs(context)
  appendLine("Last speech stage: ${stored.getString("stage", "No local speech start recorded")}")
  appendLine("Stage timestamp: ${stored.getLong("time", 0)}")
  appendLine("Available / total RAM at stage: ${stored.getLong("available", 0) / 1048576} / ${stored.getLong("total", 0) / 1048576} MiB")
  if (Build.VERSION.SDK_INT >= 30) {
   val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 3)
   for (exit in exits) {
    appendLine("Process exit: time=${exit.timestamp}, reason=${exit.reason}, status=${exit.status}, importance=${exit.importance}, PSS=${exit.pss} KiB, RSS=${exit.rss} KiB")
    appendLine(exit.description.orEmpty())
   }
  } else appendLine("Android process exit history requires Android 11+")
 }
}
