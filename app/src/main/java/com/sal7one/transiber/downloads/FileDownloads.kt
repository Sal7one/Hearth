package com.sal7one.transiber.downloads

import com.sal7one.transiber.voice.VoiceCatalog
import com.sal7one.transiber.voice.VoiceModels
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.OcrModels

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
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.models.SpeechArtifactKind
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.VoskDownloads
import com.sal7one.transiber.models.VoskPublisherPackage
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.translation.MarianPackage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Positive IDs are existing system downloads; negative IDs use public-folder downloads. */
data class FileDownload(val id: Long, val title: String, val status: Int, val reason: Int, val bytes: Long, val total: Long,
 val phase: String = "", val error: String = "", val location: String = "", val installedId: String = "", val modelId: String = "",
 val backend: String = "", val connections: Int = 1, val bytesPerSecond: Long = 0, val transferMillis: Long = 0,
 val transferredBytes: Long = 0, val retryCount: Int = 0, val networkWait: Boolean = false,
 val verificationBytes: Long = 0, val verificationTotal: Long = 0, val preparingQueued: Boolean = false,
 val preparationAction: String = "", val backendNotice: String = "") {
 val complete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
 val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
 val installing get() = phase in setOf("Installing", "Verifying")
 val paused get() = phase == "Paused"
 val installed get() = phase == "Installed"
 val active get() = phase in setOf("Waiting", "Downloading", "Verifying", "Installing") || (!complete && !failed && id > 0)
}

enum class DownloadHealth { VERIFIED, INCOMPLETE, UNVERIFIED, CHANGED, MISSING, NOT_INSTALLED }
data class DownloadInspection(val original: DownloadHealth, val installed: DownloadHealth,
 val originalError: String? = null, val installedError: String? = null)

/** Reuse a transfer by URL and model identity. Another copy requires an explicit user choice. */
internal fun reusableDownload(records: List<JSONObject>, url: String, modelId: String): JSONObject? {
 val matching = records.filter { it.optString("url") == url && it.optString("model") == modelId }
 fun newest(phases: Set<String>) = matching.asSequence().filter { it.optString("phase") in phases }
  .minByOrNull { it.optLong("id") }
 return newest(setOf("Waiting", "Downloading", "Verifying", "Installing", "Paused"))
  ?: newest(setOf("Complete", "Installed"))
  ?: newest(setOf("Failed", "Interrupted"))
}

/** Downloaded originals live in a public folder; verified runtime installs remain app-owned. */
class FileDownloads(private val context: Context) {
 private val manager = context.getSystemService(DownloadManager::class.java)
 private val prefs = context.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)
 private val locationPrefs = com.sal7one.transiber.preferences.UserPreferences.store(context, "download-location")
 // Only the former app-default selection is migrated; deliberate custom folders remain selected.
 init {
  if (Build.VERSION.SDK_INT >= 29 && locationPrefs.getString("folder_uri", null) ==
   "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FReal%20time%20transiber") {
   synchronized(lock) { check(locationPrefs.edit().remove("folder_uri").remove("folder_label").commit()) }
  }
 }
 val folderUri: Uri? get() = locationPrefs.getString("folder_uri", null)?.let(Uri::parse)
 val locationLabel: String get() = locationPrefs.getString("folder_label", null) ?: "Downloads/Hearth"
 fun chooseFolder(uri: Uri) {
  check(ByokPolicy.FEATURE_BYOK)
  val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
  context.contentResolver.takePersistableUriPermission(uri, flags)
  val folder = DocumentFile.fromTreeUri(context, uri) ?: error("Cannot open selected folder")
  check(folder.isDirectory && folder.canWrite()) { "Selected folder is not writable" }
  synchronized(lock) { check(locationPrefs.edit().putString("folder_uri", uri.toString())
   .putString("folder_label", android.provider.DocumentsContract.getTreeDocumentId(uri).replace("primary:", "Device/")).commit()) }
 }
 fun useDefaultFolder() { synchronized(lock) { check(locationPrefs.edit().remove("folder_uri").remove("folder_label").commit()) } }
 fun openFolderIntent() = Intent(Intent.ACTION_VIEW).setDataAndType(folderUri?.let { android.provider.DocumentsContract.buildDocumentUriUsingTree(it, android.provider.DocumentsContract.getTreeDocumentId(it)) } ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FHearth"), "vnd.android.document/directory")
  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

 fun existingId(spec: DownloadSpec, modelId: String = ""): Long? = synchronized(lock) {
  val checked = DownloadSpec.parse(spec.url, spec.fileName)
  reusableDownload(newIds().mapNotNull { it.toLongOrNull()?.let(::record) }, checked.url, modelId)?.optLong("id")
 }

 fun enqueue(spec: DownloadSpec, modelPackage: Boolean = false, installModelId: String = "", duplicate: Boolean = false): Long {
  com.sal7one.transiber.caption.SpeechAbiPolicy.requireCatalogModel(installModelId)
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  if (installModelId == DownloadAssets.MAGPIE_TOKENIZER) com.sal7one.transiber.voice.MagpiePolicy.requireAllowed(context, "Magpie tokenizer download")
  VoiceCatalog.find(installModelId)?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context,it.family,"speech model download") }
  if (com.sal7one.transiber.translation.Ct2Package.part(installModelId) != null) com.sal7one.transiber.translation.Ct2Package.requireRuntime(context)
  if (modelPackage || installModelId.isNotBlank()) com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model downloads")
  val checked = DownloadSpec.parse(spec.url, spec.fileName)
  check(Build.VERSION.SDK_INT >= 29 || folderUri != null) { "Choose a download folder first on Android 9" }
  if (installModelId.isNotEmpty()) {
   val expected = DownloadAssets.find(installModelId)?.url
   require(expected == checked.url) { "Download does not match the selected model" }
  }
  val (id, shouldStart) = synchronized(lock) {
   val ids = newIds()
   val existing = if (duplicate) null else reusableDownload(ids.mapNotNull { it.toLongOrNull()?.let(::record) }, checked.url, installModelId)
   if (existing != null) {
    val phase = existing.optString("phase")
    when {
     phase in setOf("Complete", "Installed") -> {
      // The foreground worker rehashes the saved original and checks the installed
      // model before restoring this state. Never trust a saved phase by itself.
      existing.put("phase", "Waiting").put("previousPhase", phase)
      check(prefs.edit().putString("download_${existing.getLong("id")}", existing.toString()).commit())
      existing.getLong("id") to true
     }
     phase in setOf("Downloading", "Verifying", "Installing") && DownloadRunner.running -> existing.getLong("id") to false
     else -> {
      existing.put("phase", "Waiting")
      existing.remove("error")
      check(prefs.edit().putString("download_${existing.getLong("id")}", existing.toString()).commit()) { "Cannot resume download" }
      existing.getLong("id") to true
     }
    }
   } else {
    var next = -System.currentTimeMillis()
    while (next.toString() in ids) next--
    val json = JSONObject().put("id", next).put("url", checked.url).put("title", checked.fileName)
     .put("model", installModelId).put("modelsFolder", modelPackage).put("tree", folderUri?.toString().orEmpty())
     .put("location", locationLabel + if (modelPackage) "/models" else "/files")
     .put("phase", "Waiting").put("bytes", 0).put("total", DownloadAssets.find(installModelId)?.bytes ?: -1)
    check(prefs.edit().putString("download_$next", json.toString()).putStringSet("public_ids", ids + next.toString()).commit()) { "Cannot save download" }
    next to true
   }
  }
  if (shouldStart) try { DownloadRunner.start(context) } catch (e: Exception) { fail(id, e); throw e }
  return id
 }
 internal fun record(id: Long): JSONObject? = synchronized(lock) { prefs.getString("download_$id", null)?.let(::JSONObject) }
 internal fun update(id: Long, change: (JSONObject) -> Unit) = synchronized(lock) {
  val json = record(id) ?: return@synchronized
  change(json)
  check(prefs.edit().putString("download_$id", json.toString()).commit()) { "Cannot save download progress" }
 }
 internal fun progress(id: Long, change: (JSONObject) -> Unit) = synchronized(lock) {
  val json = record(id) ?: throw CancellationException("Download removed")
  if (json.optString("phase") == "Paused") throw CancellationException("Download paused")
  change(json)
  check(prefs.edit().putString("download_$id", json.toString()).commit()) { "Cannot save download progress" }
 }
 internal fun fail(id: Long, error: Throwable) = update(id) {
  it.put("phase", "Failed").put("error", generateSequence(error) { cause -> cause.cause }.joinToString("\n") { cause -> cause.message ?: cause.toString() })
 }
 internal fun pendingIds() = synchronized(lock) { newIds().mapNotNull(String::toLongOrNull).filter { record(it)?.optString("phase") == "Waiting" }.sortedDescending() }
 internal fun recoverInterrupted() { synchronized(lock) { newIds().mapNotNull(String::toLongOrNull).forEach { id ->
  update(id) { if (it.optString("phase") in setOf("Downloading", "Verifying", "Installing")) it.put("phase", "Paused") }
 } } }
 fun pause(id: Long) {
  check(ByokPolicy.FEATURE_BYOK)
  update(id) { if (it.optString("phase") in setOf("Waiting", "Downloading", "Verifying", "Installing")) it.put("phase", "Paused") }
  DownloadRunner.cancel(id)
 }
 /** Pausing the queue never deletes partial files or verified installed copies. */
 fun pauseAll() {
  check(ByokPolicy.FEATURE_BYOK)
  val ids = synchronized(lock) {
   val active = newIds().mapNotNull(String::toLongOrNull).mapNotNull { id -> record(id)?.let { id to it } }
    .filter { (_, saved) -> saved.optString("phase") in setOf("Waiting", "Downloading", "Verifying", "Installing") }
   val editor = prefs.edit()
   active.forEach { (id, saved) -> editor.putString("download_$id", saved.put("phase", "Paused").toString()) }
   check(editor.commit()) { "Cannot pause download queue" }
   active.map { it.first }
  }
  ids.forEach(DownloadRunner::cancel)
 }
 fun resumeAll() = resumePhases(setOf("Paused", "Interrupted", "Waiting"))
 fun retryFailed() = resumePhases(setOf("Failed", "Interrupted"))
 private fun resumePhases(phases: Set<String>) {
  check(ByokPolicy.FEATURE_BYOK)
  val ids = synchronized(lock) {
   val candidates = newIds().mapNotNull(String::toLongOrNull).mapNotNull { id -> record(id)?.let { id to it } }
    .filter { (_, saved) -> saved.optString("phase") in phases }.sortedByDescending { it.first }
   candidates.forEach { (_, saved) ->
    val modelId = saved.optString("model")
    com.sal7one.transiber.caption.SpeechAbiPolicy.requireCatalogModel(modelId)
    if (saved.optBoolean("modelsFolder") || modelId.isNotBlank()) com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model downloads")
    if (modelId == DownloadAssets.MAGPIE_TOKENIZER) com.sal7one.transiber.voice.MagpiePolicy.requireAllowed(context, "Magpie tokenizer retry")
    VoiceCatalog.find(modelId)?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context, it.family, "speech model download retry") }
    if (com.sal7one.transiber.translation.Ct2Package.part(modelId) != null) com.sal7one.transiber.translation.Ct2Package.requireRuntime(context)
   }
   val editor = prefs.edit()
   candidates.forEach { (id, saved) -> saved.put("phase", "Waiting").remove("error"); editor.putString("download_$id", saved.toString()) }
   check(editor.commit()) { "Cannot resume download queue" }
   candidates.map { it.first }
  }
  if (ids.isNotEmpty()) DownloadRunner.start(context)
 }
 fun restart(id: Long) {
  check(ByokPolicy.FEATURE_BYOK)
  check(!list().any { it.id == id && it.active }) { "Pause the download before restarting" }
  update(id) { it.put("restart", true).put("downloadComplete", false).remove("previousPhase") }
  retry(id)
 }
 fun reinstall(id: Long) {
  check(ByokPolicy.FEATURE_BYOK)
  val saved = record(id) ?: error("Download record no longer exists")
  check(saved.optBoolean("downloadComplete") && saved.optString("model").isNotBlank()) { "A complete model download is required to reinstall" }
  check(!list().any { it.id == id && it.active }) { "Pause the download before reinstalling" }
  update(id) { it.remove("previousPhase") }
  retry(id)
 }
 fun retry(id: Long) {
  com.sal7one.transiber.caption.SpeechAbiPolicy.requireCatalogModel(record(id)?.optString("model").orEmpty())
  check(ByokPolicy.FEATURE_BYOK)
  if (record(id)?.optString("model") == DownloadAssets.MAGPIE_TOKENIZER) com.sal7one.transiber.voice.MagpiePolicy.requireAllowed(context, "Magpie tokenizer retry")
  VoiceCatalog.find(record(id)?.optString("model").orEmpty())?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context,it.family,"speech model download retry") }
  if (com.sal7one.transiber.translation.Ct2Package.part(record(id)?.optString("model").orEmpty()) != null)
   com.sal7one.transiber.translation.Ct2Package.requireRuntime(context)
  record(id)?.let { if (it.optBoolean("modelsFolder") || it.optString("model").isNotBlank())
   com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model downloads") }
  update(id) { it.put("phase", "Waiting").remove("error") }
  try { DownloadRunner.start(context) } catch (e: Exception) { fail(id, e); throw e }
 }
 /** Inspect the actual public file on IO before offering reuse, repair or another copy. */
 suspend fun inspect(id: Long, checkOriginal: Boolean = true): DownloadInspection = withContext(Dispatchers.IO) {
  val saved = record(id) ?: error("Download record no longer exists")
  val phase = saved.optString("phase")
  val asset = DownloadAssets.find(saved.optString("model"))
  val expected = asset?.let { DownloadIntegrity.Fingerprint(it.bytes, it.sha256) }
   ?: saved.optString("sha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    ?.let { DownloadIntegrity.Fingerprint(saved.optLong("verifiedBytes", -1), it) }
  val job = currentCoroutineContext()
  var originalError: String? = null
  val original = when {
   !checkOriginal -> DownloadHealth.VERIFIED
   phase in setOf("Waiting", "Downloading", "Verifying", "Installing", "Paused", "Interrupted", "Failed") && !saved.optBoolean("downloadComplete") -> DownloadHealth.INCOMPLETE
   expected == null -> DownloadHealth.UNVERIFIED
   else -> try {
    val input = context.contentResolver.openInputStream(uri(id)) ?: error("Cannot open downloaded file")
    val actual = input.use { DownloadIntegrity.fingerprint(it, expected.bytes.coerceAtLeast(1)) { job.ensureActive() } }
    if (actual == expected) DownloadHealth.VERIFIED else DownloadHealth.CHANGED
   } catch (e: CancellationException) { throw e }
     catch (e: IllegalArgumentException) { originalError = e.message ?: e.toString(); DownloadHealth.CHANGED }
     catch (e: Exception) { originalError = e.message ?: e.toString(); DownloadHealth.MISSING }
  }
  val installedId = saved.optString("installed")
  var installedError: String? = null
  val installed = if (installedId.isBlank()) DownloadHealth.NOT_INSTALLED else try {
   val modelId = saved.optString("model")
   when {
    com.sal7one.transiber.translation.Ct2Package.part(modelId) != null -> {
     val part = checkNotNull(com.sal7one.transiber.translation.Ct2Package.part(modelId))
     require(installedId == part.spec.id) { "Installed CT2 model identity has changed" }
     part.spec.verify(com.sal7one.transiber.translation.Ct2Package.directory(context, part.spec))
    }
    com.sal7one.transiber.translation.MarianPackage.part(modelId) != null ->
     ModelRegistry.getInstance(context).getProvider(installedId)?.getModelPath() ?: error("Installed model folder is missing")
    com.sal7one.transiber.models.SpeechComponents.forPart(modelId) != null -> {
     val group = checkNotNull(com.sal7one.transiber.models.SpeechComponents.forPart(modelId))
     val root = File(context.filesDir, "speech-models")
     val model = LocalSpeechModels(root).select(group.profile.captionEngine, installedId)
     check(com.sal7one.transiber.models.SpeechCatalogInstallations(root).matchesComponents(model, group, true)) { "Installed speech components have changed" }
    }
    SpeechDownloads.find(modelId) != null ->
     com.sal7one.common_jni.speech.SpeechModelPackage.verify(File(context.filesDir, "speech-models/$installedId"))
    TranslationCatalog.models.any { it.id == modelId } -> {
     val spec = TranslationCatalog.models.first { it.id == modelId }
     com.sal7one.common_jni.model.ModelIntegrity.inspect(LocalTranslationModels(File(context.filesDir, "translation-models")).file(spec), spec.sha256)
    }
    modelId == DownloadAssets.MAGPIE_TOKENIZER -> {
     val job = currentCoroutineContext()
     val root = File(context.filesDir, "voice-models/magpie/tokenizer")
     com.sal7one.hearth.tts.magpie.MagpieCatalog.tokenizer.forEach { part ->
      val actual = File(root, part.filename).inputStream().use { input -> DownloadIntegrity.fingerprint(input, part.bytes) { job.ensureActive() } }
      check(actual == DownloadIntegrity.Fingerprint(part.bytes, part.sha256)) { "Installed Magpie tokenizer changed: ${part.filename}" }
     }
    }
    OcrCatalog.find(modelId) != null -> {
     val part = checkNotNull(OcrCatalog.find(modelId))
     val file = OcrModels(File(context.filesDir, "ocr-models")).file(part)
     check(file.inputStream().use { DownloadIntegrity.matches(it, checkNotNull(asset)) }) { "Installed OCR checksum failed" }
    }
    VoiceCatalog.find(modelId) != null -> {
     val part = checkNotNull(VoiceCatalog.find(modelId))
     VoiceModels(File(context.filesDir, "voice-models")).verifyAsset(part)
    }
    else -> ModelRegistry.getInstance(context).getProvider(installedId)?.getModelPath() ?: error("Installed model is missing")
   }
   DownloadHealth.VERIFIED
  } catch (e: CancellationException) { throw e }
    catch (e: Exception) { installedError = e.message ?: e.toString(); DownloadHealth.MISSING }
  DownloadInspection(original, installed, originalError, installedError)
 }
 fun list(): List<FileDownload> {
  if (!ByokPolicy.FEATURE_BYOK) return emptyList()
  val local = synchronized(lock) { newIds().mapNotNull { it.toLongOrNull()?.let(::record) }.map { j ->
   val savedPhase = j.getString("phase")
   val interrupted = !DownloadRunner.running && (savedPhase in setOf("Downloading", "Verifying", "Installing") ||
       (savedPhase == "Waiting" && System.currentTimeMillis() + j.getLong("id") > 5000))
   val phase = if (interrupted) "Interrupted" else savedPhase
   FileDownload(j.getLong("id"), j.getString("title"), when (phase) {
    "Complete", "Installed" -> DownloadManager.STATUS_SUCCESSFUL
    "Failed", "Interrupted" -> DownloadManager.STATUS_FAILED
    "Waiting" -> DownloadManager.STATUS_PENDING
    "Paused" -> DownloadManager.STATUS_PAUSED
    else -> DownloadManager.STATUS_RUNNING
   }, 0, j.optLong("bytes"), j.optLong("total", -1), phase, if (interrupted) "Transfer interrupted. Tap Retry to finish." else listOf(j.optString("error"), j.optString("diagnosticError")).filter(String::isNotBlank).joinToString("\n"), j.optString("location"), j.optString("installed"), j.optString("model"), j.optString("backend"),
    j.optInt("connections", 1), j.optLong("bytesPerSecond"), j.optLong("transferMillis"), j.optLong("transferredBytes"), j.optInt("retries"), j.optBoolean("networkWait"),
    j.optLong("verificationBytes"), j.optLong("verificationTotal"), j.optBoolean("preparingQueued"), j.optString("preparationAction"), j.optString("backendNotice"))
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
  File(context.filesDir, "download-staging/$id.part").delete()
  File(context.filesDir, "download-staging/$id.part.aria2").delete()
 }
 suspend fun installTranslation(id: Long, spec: TranslationModelSpec) = withContext(Dispatchers.IO) {
  check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
  com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model installation")
  val job = currentCoroutineContext()
  context.contentResolver.openInputStream(uri(id))?.use {
   LocalTranslationModels(File(context.filesDir, "translation-models")).import(it, spec) { job.ensureActive() }
  } ?: error("Cannot open downloaded translation model")
 }
 internal suspend fun installModel(id: Long, modelId: String): String = withContext(Dispatchers.IO) {
  com.sal7one.transiber.caption.SpeechAbiPolicy.requireCatalogModel(modelId)
  com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model installation")
  VoiceCatalog.find(modelId)?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context,it.family,"speech model install") }
  com.sal7one.transiber.translation.Ct2Package.part(modelId)?.let { part ->
   return@withContext com.sal7one.transiber.translation.Ct2Package.install(context, this@FileDownloads, part.spec).orEmpty()
  }
  MarianPackage.pairForPart(modelId)?.let { pair ->
   return@withContext MarianPackage.install(context, this@FileDownloads, pair).orEmpty()
  }
  com.sal7one.transiber.models.SpeechComponents.forPart(modelId)?.let { group ->
   return@withContext com.sal7one.transiber.models.SpeechComponents.install(context, this@FileDownloads, group)?.id.orEmpty()
  }
  if (modelId == DownloadAssets.MAGPIE_TOKENIZER) {
   com.sal7one.transiber.voice.MagpiePolicy.requireAllowed(context, "Magpie tokenizer installation")
   val job = currentCoroutineContext()
   val root = File(context.filesDir, "voice-models/magpie").apply { check(isDirectory || mkdirs()) }
   val payload = File.createTempFile("tokenizer-ranges-", ".part", root)
   try {
    val expected = checkNotNull(DownloadAssets.find(modelId))
    context.contentResolver.openInputStream(uri(id))?.use { input -> payload.outputStream().use { output ->
     val buffer = ByteArray(65536); var bytes = 0L
     while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break
      bytes = Math.addExact(bytes, n.toLong()); check(bytes <= expected.bytes) { "Tokenizer payload exceeds its pin" }; output.write(buffer, 0, n) }
     check(bytes == expected.bytes) { "Tokenizer payload is truncated" }
    } } ?: error("Cannot open downloaded Magpie tokenizer ranges")
    com.sal7one.hearth.tts.magpie.MagpieModels(root).installTokenizerSegments(payload) { job.ensureActive() }
    for (part in com.sal7one.hearth.tts.magpie.MagpieCatalog.tokenizer)
     com.sal7one.transiber.preferences.ModelSourceBindings.recordImported(context, "voice-magpie-tokenizer-${part.filename}", uri(id), listOf(File(root, "tokenizer/${part.filename}")))
    return@withContext modelId
   } finally { payload.delete() }
  }
  VoiceCatalog.find(modelId)?.let { asset ->
   val job = currentCoroutineContext()
   val installed = context.contentResolver.openInputStream(uri(id))?.use { input ->
    VoiceModels(File(context.filesDir,"voice-models"),permitFamily={com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context,it,"speech model installation")},permitOmniVoice={com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context,"omnivoice","OmniVoice model installation")}){com.sal7one.transiber.voice.MagpiePolicy.requireAllowed(context,"Magpie model installation")}.import(input,asset) {job.ensureActive()}
   } ?: error("Cannot open downloaded voice model")
   return@withContext installed.id
  }
  OcrCatalog.find(modelId)?.let { asset ->
   val job = currentCoroutineContext()
   val installed = context.contentResolver.openInputStream(uri(id))?.use { input ->
    OcrModels(File(context.filesDir, "ocr-models")).import(input, asset) { job.ensureActive() }
   } ?: error("Cannot open downloaded OCR model")
   return@withContext installed.id
  }
  val speech = SpeechDownloads.find(modelId)
  if (speech != null) {
   val job = currentCoroutineContext()
   val model = context.contentResolver.openInputStream(uri(id))?.use {
    LocalSpeechModels(File(context.filesDir, "speech-models")).installPublisher(it, speech, id) { job.ensureActive() }
   } ?: error("Cannot open downloaded speech model")
   model.id
  } else {
   val artifact = SpeechArtifactCatalog.find(modelId)
   if (artifact?.kind == SpeechArtifactKind.VOSK) {
    val source = checkNotNull(VoskDownloads.find(modelId)) { "Unknown pinned Vosk package" }
    val registry = ModelRegistry.getInstance(context)
    registry.getModelsForEngine(ModelEngineType.VOSK).filter { it.digest?.hex == source.treeSha256 }.forEach { existing ->
     if (runCatching { registry.getProvider(existing.id)?.getModelPath() ?: error("Installed model is missing") }.isFailure)
      registry.unregisterModel(existing.id, deleteFiles = true)
    }
    val job = currentCoroutineContext()
    val work = File(context.cacheDir, "vosk-publisher-$id")
    check(!work.exists() || work.deleteRecursively()) { "Cannot clear interrupted Vosk installation" }
    check(work.mkdirs()) { "Cannot create Vosk installation staging directory" }
    try {
     val imported = registry.registerPinnedDirectory(checkNotNull(artifact.archiveRoot), source.treeSha256) { sink ->
      context.contentResolver.openInputStream(uri(id))?.use { input ->
       VoskPublisherPackage.populate(input, source, work, sink) { job.ensureActive() }
      } ?: error("Cannot open downloaded Vosk archive")
     }
     require(imported.engineType == ModelEngineType.VOSK && imported.isValid) { "Downloaded package is not a supported Vosk model" }
     return@withContext imported.id
    } finally { work.deleteRecursively() }
   }
   if (artifact?.kind == SpeechArtifactKind.WHISPER) {
    val expected = artifact.sha256
    val registry = ModelRegistry.getInstance(context)
    record(id)?.optString("installed")?.takeIf(String::isNotBlank)?.let { installedId ->
     if (runCatching { registry.getProvider(installedId)?.getModelPath() ?: error("Installed model is missing") }.isFailure)
      registry.unregisterModel(installedId, deleteFiles = true)
    }
    val imported = registry.importModel(uri(id), expected)
        ?: error("Whisper model was not registered")
    require(imported.engineType == ModelEngineType.WHISPER && imported.isValid) { "Downloaded file is not a supported Whisper model" }
    return@withContext imported.id
   }
   val spec = TranslationCatalog.models.firstOrNull { it.id == modelId } ?: error("Unknown downloadable model: $modelId")
   installTranslation(id, spec)
   spec.id
  }
 }
 /** Installing is deliberately separate from activating: a late background completion must not change a running session. */
 suspend fun selectInstalled(item: FileDownload) {
  com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model selection")
  val modelId = record(item.id)?.optString("model").orEmpty()
  com.sal7one.transiber.caption.SpeechAbiPolicy.requireCatalogModel(modelId)
  com.sal7one.transiber.translation.Ct2Package.part(modelId)?.let { part ->
   com.sal7one.transiber.translation.Ct2Package.requireRuntime(context)
   require(com.sal7one.transiber.translation.Ct2Package.installed(context, part.spec)) { "Finish installing all CT2 model files first" }
   CaptionConfigStore.update(context) { it.copy(localTranslationModelId = part.spec.id,
    textTranslationProviderId = "local", localTranslationEnabled = true, mode = CaptionMode.TRANSLATE,
    target = if(part.spec.targets.size==1) TranslationTarget.of(part.spec.targets.single()) else it.target) }
   return
  }
  MarianPackage.pairForPart(modelId)?.let { pair ->
   require(MarianPackage.installed(context, pair) != null) { "Finish installing the Marian model files first" }
   CaptionConfigStore.update(context) { it.copy(localTranslationModelId = pair.id,
    textTranslationProviderId = "local", localTranslationEnabled = true,
    mode = CaptionMode.TRANSLATE, target = TranslationTarget.of(pair.target)) }
   return
  }
  VoiceCatalog.find(modelId)?.let { asset ->
   require(item.installed) { "Voice model is not installed" }
   if (asset.family != "supertonic") {
    val family=asset.family
    com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(context,family)
    val selected=com.sal7one.transiber.voice.VoiceSettings.choice(context).copy(backend="supertonic",family=family,voice=com.sal7one.transiber.voice.VoiceSettings.familyVoice(context,family))
    require(com.sal7one.transiber.voice.LocalVoiceFamilies.ready(context,selected)) { "Install every ${com.sal7one.transiber.voice.LocalVoiceFamilies.label(family)} component before selecting it" }
    val job=currentCoroutineContext()
    withContext(Dispatchers.IO) { com.sal7one.transiber.voice.LocalVoiceFamilies.verify(context,selected) { job.ensureActive() } }
    com.sal7one.transiber.voice.VoiceSettings.save(context,selected)
    return
   }
   val voice=asset.filename.removeSuffix(".json").takeIf {it in VoiceCatalog.voices} ?: "F1"
   require(VoiceModels(File(context.filesDir,"voice-models")).ready(voice)) { "Finish downloading the voice engine before selecting it" }
   com.sal7one.transiber.preferences.UserPreferences.store(context, "voice-output").edit().putString("backend","supertonic").putString("local-family","supertonic").putString("voice",voice).apply()
   return
  }
  OcrCatalog.find(modelId)?.let { asset ->
   require(item.installed) { "OCR model is not installed" }
   if(asset.id == OcrCatalog.detector.id)return
   val selected=com.sal7one.transiber.ocr.OcrPreferences(context).read().profileId
   val candidates=OcrCatalog.profiles.filter { profile -> profile.assets.any { it.id == asset.id } }
   val ocrModels=OcrModels(File(context.filesDir,"ocr-models"))
   // Shared Manga reader files serve both automatic pages and manual bubbles.
   // Respect the selected profile; a component's Use button must not force its new sibling.
   (candidates.firstOrNull {it.id==selected} ?: candidates.firstOrNull(ocrModels::ready)
      ?: candidates.minByOrNull {it.assets.size})?.let { profile ->
    require(OcrModels(File(context.filesDir, "ocr-models")).ready(profile)) { "Finish installing all files for ${profile.label} first" }
    val settings = com.sal7one.transiber.preferences.UserPreferences.store(context, "camera-translate")
    val source = settings.getString("source", "en")
    settings.edit().putString("profile", profile.id).putString("source", source?.takeIf { it in profile.languages } ?: profile.languages.first()).apply()
   }
   return
  }
  com.sal7one.transiber.models.SpeechComponents.forPart(modelId)?.let { group ->
   require(item.installed && item.installedId.isNotBlank()) { "Speech model is not installed" }
   val job = currentCoroutineContext()
   withContext(Dispatchers.IO) {
    val root = File(context.filesDir, "speech-models")
    val model = LocalSpeechModels(root).select(group.profile.captionEngine, item.installedId)
    job.ensureActive()
    check(com.sal7one.transiber.models.SpeechCatalogInstallations(root).matchesComponents(model, group, true)) { "Installed speech component checksum failed" }
   }
   CaptionConfigStore.update(context) { it.copy(engine = group.profile.captionEngine, modelId = item.installedId, streamLanguage = "auto") }
   return
  }
  val speech = SpeechDownloads.find(modelId)
  val artifact = SpeechArtifactCatalog.find(modelId)
  if (artifact?.kind == SpeechArtifactKind.VOSK) {
   require(item.installed && item.installedId.isNotBlank()) { "Vosk model is not installed" }
   val model = ModelRegistry.getInstance(context).getModel(item.installedId)
   val source = checkNotNull(VoskDownloads.find(modelId))
   require(model?.isValid == true && model.engineType == ModelEngineType.VOSK && model.digest?.hex == source.treeSha256) {
    "Installed Vosk model is unavailable"
   }
   CaptionConfigStore.update(context) { it.copy(engine = com.sal7one.transiber.caption.CaptionEngineChoice.VOSK,
    modelId = item.installedId, streamLanguage = source.language) }
   return
  }
  if (artifact?.kind == SpeechArtifactKind.WHISPER) {
   require(item.installed && item.installedId.isNotBlank()) { "Whisper model is not installed" }
   val model = ModelRegistry.getInstance(context).getModel(item.installedId)
   require(model?.isValid == true && model.engineType == ModelEngineType.WHISPER) { "Installed Whisper model is unavailable" }
   CaptionConfigStore.update(context) { it.copy(engine = com.sal7one.transiber.caption.CaptionEngineChoice.WHISPER,
    modelId = item.installedId, streamLanguage = "auto") }
   return
  }
  require(item.installed && item.installedId.isNotBlank()) { "Model is not installed" }
  CaptionConfigStore.update(context) {
   if (speech != null) it.copy(engine = speech.profile.captionEngine, modelId = item.installedId, streamLanguage = if (speech.profile.capabilities.automaticSourceLanguage) "auto" else "ar")
   else it.copy(localTranslationModelId = item.installedId)
  }
 }
 fun translationModel(record: FileDownload): TranslationModelSpec? = TranslationCatalog.models.firstOrNull { if (record.modelId.isNotBlank()) it.id == record.modelId else it.fileName == record.title }
 fun uri(id: Long): Uri = if (id > 0) manager.getUriForDownloadedFile(id) ?: error("Downloaded file is not available")
  else record(id)?.optString("uri")?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: error("Downloaded file is not available")
 private fun newIds() = prefs.getStringSet("public_ids", emptySet()).orEmpty().toSet()
 companion object { private val lock = Any() }
}
