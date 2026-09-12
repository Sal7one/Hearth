package com.sal7one.transiber.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Build
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationModelSpec
import com.sal7one.transiber.translation.LocalTranslationModels
import kotlinx.coroutines.*
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
 @Synchronized fun enqueue(spec: DownloadSpec, modelPackage: Boolean = false): Long {
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  val checked = DownloadSpec.parse(spec.url, spec.fileName)
  val relative = checked.destination(modelPackage, UUID.randomUUID().toString())
  val request = DownloadManager.Request(Uri.parse(checked.url))
   .setTitle(checked.fileName)
   .setDescription("Real time transiber file download")
   .setAllowedOverMetered(true)
   .setAllowedOverRoaming(false)
   .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
  if (Build.VERSION.SDK_INT >= 29) {
   request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relative)
  } else {
   // Android 9 needs broad storage permission for public downloads. Preserve the
   // permission-free legacy destination; direct installation still works.
   request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, relative)
  }
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
 val locationLabel: String get() = if (Build.VERSION.SDK_INT >= 29)
  "Downloads/Real time transiber" else "App storage (Android 9)"
 suspend fun installTranslation(id: Long, spec: TranslationModelSpec) = withContext(Dispatchers.IO) {
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  val record = list().firstOrNull { it.id == id } ?: error("Download is no longer available")
  check(record.complete) { "Download has not completed" }
  check(record.title == spec.fileName) { "Download does not match the selected model" }
  val job = currentCoroutineContext()
  context.contentResolver.openInputStream(uri(id))?.use {
   LocalTranslationModels(java.io.File(context.filesDir, "translation-models")).import(it, spec) { job.ensureActive() }
  } ?: error("Cannot open downloaded translation model")
 }
 fun translationModel(record: FileDownload): TranslationModelSpec? =
  TranslationCatalog.models.firstOrNull { it.fileName == record.title }
 fun uri(id: Long): Uri = manager.getUriForDownloadedFile(id) ?: error("Downloaded file is not available")
}
