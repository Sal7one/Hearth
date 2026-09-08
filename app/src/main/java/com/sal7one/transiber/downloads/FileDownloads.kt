package com.sal7one.transiber.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.sal7one.transiber.byok.ByokPolicy
import java.util.UUID

data class FileDownload(val id: Long, val title: String, val status: Int, val reason: Int, val bytes: Long, val total: Long) {
 val complete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
 val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
}

/** System-owned downloads survive navigation and process restarts. Stores IDs, never API keys. */
class FileDownloads(private val context: Context) {
 private val manager = context.getSystemService(DownloadManager::class.java)
 private val prefs = context.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)
 @Synchronized fun enqueue(spec: DownloadSpec): Long {
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  val checked = DownloadSpec.parse(spec.url, spec.fileName)
  val directory = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
   ?: error("App download storage is unavailable"), UUID.randomUUID().toString())
  check(directory.mkdirs()) { "Cannot create download directory" }
  val request = DownloadManager.Request(Uri.parse(checked.url))
   .setTitle(checked.fileName)
   .setDescription("Real time transiber file download")
   .setAllowedOverMetered(true)
   .setAllowedOverRoaming(false)
   .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
   .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "${directory.name}/${checked.fileName}")
  val id = manager.enqueue(request)
  val ids = prefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet().apply { add(id.toString()) }
  if (!prefs.edit().putStringSet("ids", ids).commit()) { manager.remove(id); error("Cannot persist download ID") }
  return id
 }
 fun list(): List<FileDownload> {
  if (!ByokPolicy.FEATURE_BYOK) return emptyList()
  val ids = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
  if (ids.isEmpty()) return emptyList()
  return buildList {
   manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
    fun number(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
    while(c.moveToNext()) add(FileDownload(number(DownloadManager.COLUMN_ID), c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)), number(DownloadManager.COLUMN_STATUS).toInt(), number(DownloadManager.COLUMN_REASON).toInt(), number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
   }
  }.sortedByDescending { it.id }
 }
 @Synchronized fun remove(id: Long) {
  check(ByokPolicy.FEATURE_BYOK)
  manager.remove(id)
  prefs.edit().putStringSet("ids", prefs.getStringSet("ids", emptySet()).orEmpty() - id.toString()).commit()
 }
 fun uri(id: Long): Uri = manager.getUriForDownloadedFile(id) ?: error("Downloaded file is not available")
}
