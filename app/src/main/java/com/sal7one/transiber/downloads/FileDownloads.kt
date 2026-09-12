package com.sal7one.transiber.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationModelSpec
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.models.SpeechDownloads
import com.sal7one.transiber.translation.LocalTranslationModels
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Positive IDs are existing system downloads; negative IDs use public-folder downloads. */
data class FileDownload(val id: Long, val title: String, val status: Int, val reason: Int, val bytes: Long, val total: Long,
 val phase: String = "", val error: String = "", val location: String = "", val installedId: String = "") {
 val complete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
 val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
 val installing get() = phase == "Installing"
 val installed get() = phase == "Installed"
 val active get() = phase in setOf("Waiting", "Downloading", "Installing") || (!complete && !failed && id > 0)
}

/** Downloaded originals live in a public folder; verified runtime installs remain app-owned. */
class FileDownloads(private val context: Context) {
 private val manager = context.getSystemService(DownloadManager::class.java)
 private val prefs = context.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)
 // Only the former app-default selection is migrated; deliberate custom folders remain selected.
 init {
  if (Build.VERSION.SDK_INT >= 29 && prefs.getString("folder_uri", null) ==
   "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FReal%20time%20transiber") {
   synchronized(lock) { check(prefs.edit().remove("folder_uri").remove("folder_label").commit()) }
  }
 }
 val folderUri: Uri? get() = prefs.getString("folder_uri", null)?.let(Uri::parse)
 val locationLabel: String get() = prefs.getString("folder_label", null) ?: "Downloads/Hearth"
 fun chooseFolder(uri: Uri) {
  check(ByokPolicy.FEATURE_BYOK)
  val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
  context.contentResolver.takePersistableUriPermission(uri, flags)
  val folder = DocumentFile.fromTreeUri(context, uri) ?: error("Cannot open selected folder")
  check(folder.isDirectory && folder.canWrite()) { "Selected folder is not writable" }
  synchronized(lock) { check(prefs.edit().putString("folder_uri", uri.toString())
   .putString("folder_label", android.provider.DocumentsContract.getTreeDocumentId(uri).replace("primary:", "Device/")).commit()) }
 }
 fun useDefaultFolder() { synchronized(lock) { check(prefs.edit().remove("folder_uri").remove("folder_label").commit()) } }
 fun openFolderIntent() = Intent(Intent.ACTION_VIEW).setDataAndType(folderUri?.let { android.provider.DocumentsContract.buildDocumentUriUsingTree(it, android.provider.DocumentsContract.getTreeDocumentId(it)) } ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FHearth"), "vnd.android.document/directory")
  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

 fun enqueue(spec: DownloadSpec, modelPackage: Boolean = false, installModelId: String = ""): Long {
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  val checked = DownloadSpec.parse(spec.url, spec.fileName)
  check(Build.VERSION.SDK_INT >= 29 || folderUri != null) { "Choose a download folder first on Android 9" }
  if (installModelId.isNotEmpty()) {
   val expected = SpeechDownloads.find(installModelId)?.url ?: TranslationCatalog.models.firstOrNull { it.id == installModelId }?.url
   require(expected == checked.url) { "Download does not match the selected model" }
  }
  val id = synchronized(lock) {
   val ids = newIds()
   var next = -System.currentTimeMillis()
   while (next.toString() in ids) next--
   val json = JSONObject().put("id", next).put("url", checked.url).put("title", checked.fileName)
    .put("model", installModelId).put("modelsFolder", modelPackage).put("tree", folderUri?.toString().orEmpty())
    .put("location", locationLabel + if (modelPackage) "/models" else "/files")
    .put("phase", "Waiting").put("bytes", 0).put("total", -1)
   check(prefs.edit().putString("download_$next", json.toString()).putStringSet("public_ids", ids + next.toString()).commit()) { "Cannot save download" }
   next
  }
  try { DownloadRunner.start(context) } catch (e: Exception) { fail(id, e); throw e }
  return id
 }
 internal fun record(id: Long): JSONObject? = synchronized(lock) { prefs.getString("download_$id", null)?.let(::JSONObject) }
 internal fun update(id: Long, change: (JSONObject) -> Unit) = synchronized(lock) {
  val json = record(id) ?: return@synchronized
  change(json)
  check(prefs.edit().putString("download_$id", json.toString()).commit()) { "Cannot save download progress" }
 }
 internal fun fail(id: Long, error: Throwable) = update(id) {
  it.put("phase", "Failed").put("error", generateSequence(error) { cause -> cause.cause }.joinToString("\n") { cause -> cause.message ?: cause.toString() })
 }
 internal fun pendingIds() = synchronized(lock) { newIds().mapNotNull(String::toLongOrNull).filter { record(it)?.optString("phase") == "Waiting" }.sortedDescending() }
 internal fun recoverInterrupted() { synchronized(lock) { newIds().mapNotNull(String::toLongOrNull).forEach { id ->
  update(id) { if (it.optString("phase") in setOf("Downloading", "Installing")) it.put("phase", "Waiting") }
 } } }
 fun retry(id: Long) {
  check(ByokPolicy.FEATURE_BYOK)
  update(id) { it.put("phase", "Waiting").remove("error") }
  try { DownloadRunner.start(context) } catch (e: Exception) { fail(id, e); throw e }
 }
 fun list(): List<FileDownload> {
  if (!ByokPolicy.FEATURE_BYOK) return emptyList()
  val local = synchronized(lock) { newIds().mapNotNull { it.toLongOrNull()?.let(::record) }.map { j ->
   val savedPhase = j.getString("phase")
   val interrupted = !DownloadRunner.running && (savedPhase in setOf("Downloading", "Installing") ||
       (savedPhase == "Waiting" && System.currentTimeMillis() + j.getLong("id") > 5000))
   val phase = if (interrupted) "Interrupted" else savedPhase
   FileDownload(j.getLong("id"), j.getString("title"), when (phase) {
    "Complete", "Installed" -> DownloadManager.STATUS_SUCCESSFUL
    "Failed", "Interrupted" -> DownloadManager.STATUS_FAILED
    "Waiting" -> DownloadManager.STATUS_PENDING
    else -> DownloadManager.STATUS_RUNNING
   }, 0, j.optLong("bytes"), j.optLong("total", -1), phase, if (interrupted) "Transfer interrupted. Tap Retry to finish." else j.optString("error"), j.optString("location"), j.optString("installed"))
  } }
  val ids = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
  val legacy = if (ids.isEmpty()) emptyList() else buildList {
   manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
    fun number(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
    while(c.moveToNext()) add(FileDownload(number(DownloadManager.COLUMN_ID), c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)), number(DownloadManager.COLUMN_STATUS).toInt(), number(DownloadManager.COLUMN_REASON).toInt(), number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), location = "Earlier download"))
   }
  }
  return local.sortedBy { it.id } + legacy.sortedByDescending { it.id }
 }
 fun remove(id: Long) {
  check(ByokPolicy.FEATURE_BYOK)
  if (id > 0) { synchronized(lock) { manager.remove(id); check(prefs.edit().putStringSet("ids", prefs.getStringSet("ids", emptySet()).orEmpty() - id.toString()).commit()) }; return }
  DownloadRunner.cancel(id)
  record(id)?.optString("uri")?.takeIf(String::isNotBlank)?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
  synchronized(lock) { check(prefs.edit().remove("download_$id").putStringSet("public_ids", newIds() - id.toString()).commit()) }
 }
 suspend fun installTranslation(id: Long, spec: TranslationModelSpec) = withContext(Dispatchers.IO) {
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  val job = currentCoroutineContext()
  context.contentResolver.openInputStream(uri(id))?.use {
   LocalTranslationModels(File(context.filesDir, "translation-models")).import(it, spec) { job.ensureActive() }
  } ?: error("Cannot open downloaded translation model")
 }
 internal suspend fun installModel(id: Long, modelId: String): String = withContext(Dispatchers.IO) {
  val speech = SpeechDownloads.find(modelId)
  if (speech != null) {
   val job = currentCoroutineContext()
   val model = context.contentResolver.openInputStream(uri(id))?.use {
    LocalSpeechModels(File(context.filesDir, "speech-models")).installPublisher(it, speech, id) { job.ensureActive() }
   } ?: error("Cannot open downloaded speech model")
   model.id
  } else {
   val spec = TranslationCatalog.models.firstOrNull { it.id == modelId } ?: error("Unknown downloadable model: $modelId")
   installTranslation(id, spec)
   spec.id
  }
 }
 /** Installing is deliberately separate from activating: a late background completion must not change a running session. */
 suspend fun selectInstalled(item: FileDownload) {
  val modelId = record(item.id)?.optString("model").orEmpty()
  val speech = SpeechDownloads.find(modelId)
  require(item.installed && item.installedId.isNotBlank()) { "Model is not installed" }
  CaptionConfigStore.update(context) {
   if (speech != null) it.copy(engine = speech.profile.captionEngine, modelId = item.installedId, streamLanguage = "auto")
   else it.copy(localTranslationModelId = item.installedId)
  }
 }
 fun translationModel(record: FileDownload): TranslationModelSpec? = TranslationCatalog.models.firstOrNull { it.fileName == record.title }
 fun uri(id: Long): Uri = if (id > 0) manager.getUriForDownloadedFile(id) ?: error("Downloaded file is not available")
  else record(id)?.optString("uri")?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: error("Downloaded file is not available")
 private fun newIds() = prefs.getStringSet("public_ids", emptySet()).orEmpty().toSet()
 companion object { private val lock = Any() }
}
