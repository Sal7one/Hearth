package com.sal7one.transiber.downloads

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.models.SpeechDownloads
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.common_jni.translation.TranslationCatalog
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import android.system.Os
import android.system.OsConstants

/** User-started transfers stream directly into public storage; preparation runs in the same notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var downloads: FileDownloads
    private var drain: Job? = null
    private var lastStart = 0
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        running = true
        downloads = FileDownloads(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Model and file downloads", NotificationManager.IMPORTANCE_LOW))
        downloads.recoverInterrupted()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val notification = notification("Starting downloads")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
        if (drain?.isActive != true) drain = scope.launch {
            try {
                while (true) {
                    val id = downloads.pendingIds().firstOrNull() ?: break
                    val task = launch(Dispatchers.IO) {
                        try { runDownload(id) }
                        catch (e: CancellationException) { if (downloads.record(id)?.optString("phase") !in setOf(null, "Paused", "Waiting")) downloads.fail(id, IllegalStateException("Download interrupted. Tap Retry to continue.")); throw e }
                        catch (e: Exception) { if (downloads.record(id)?.optString("phase") !in setOf(null, "Paused")) downloads.fail(id, e) }
                    }
                    jobs[id] = task
                    task.join()
                    jobs.remove(id)
                }
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStart) }
        }
        return START_REDELIVER_INTENT
    }
    private suspend fun runDownload(id: Long) {
        val record = downloads.record(id) ?: return
        if (record.optString("phase") != "Waiting") return
        val modelId = record.optString("model")
        val asset = DownloadAssets.find(modelId)
        var complete = record.optBoolean("downloadComplete")
        if (!complete) {
            var destination = record.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)
            var offset = 0L
            if (destination != null && !record.optBoolean("restart")) {
                // Resume only seekable destinations; never assume SAF append support.
                offset = runCatching { contentResolver.openFileDescriptor(destination!!, "rw")?.use {
                    val size = Os.lseek(it.fileDescriptor, 0, OsConstants.SEEK_END)
                    require(size >= 0); size
                } ?: 0L }.getOrElse {
                    error("This folder cannot resume the partial download. Choose Restart download.")
                }
            }
            // A direct URL has no pinned hash. Restart it instead of joining bytes
            // from potentially different server revisions into one file.
            if (record.optBoolean("restart") || (asset == null && offset > 0) || (asset != null && offset > asset.bytes)) {
                destination?.let { contentResolver.delete(it, null, null) }
                destination = null; offset = 0
            }
            if (destination == null) destination = createDestination(record)
            val target = checkNotNull(destination)
            DownloadStorage.requireSpace(this, asset, offset, record.optString("tree").isNotBlank())
            downloads.progress(id) { it.put("uri", target.toString()).put("phase", "Downloading").put("bytes", offset); it.remove("restart"); it.remove("error") }
            val title = record.getString("title")
            publish("Downloading $title")
            val checked = DownloadSpec.parse(record.getString("url"), title)
            val validator = record.optString("validator").takeIf(String::isNotBlank)
            // A fully transferred file may only need verification after process death.
            if (asset == null || offset != asset.bytes) {
                val request = Request.Builder().url(checked.url).header("Accept-Encoding", "identity")
                if (offset > 0) {
                    request.header("Range", "bytes=$offset-")
                    validator?.let { request.header("If-Range", it) }
                }
                val call = client.newCall(request.build())
                val cancel = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code} ${response.message}" }
                        val body = response.body ?: error("Download response has no body")
                        val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                            ?: response.header("Last-Modified")
                        val transfer = DownloadResume.response(response.code, offset, response.header("Content-Range"),
                            body.contentLength(), asset?.bytes, validator, responseValidator)
                        var bytes = transfer.offset
                        downloads.progress(id) { it.put("bytes", bytes).put("total", transfer.total)
                            if (responseValidator != null) it.put("validator", responseValidator) else it.remove("validator") }
                        contentResolver.openFileDescriptor(target, if (transfer.offset == 0L) "rwt" else "rw")?.use { descriptor ->
                            if (transfer.offset > 0) check(Os.lseek(descriptor.fileDescriptor, transfer.offset, OsConstants.SEEK_SET) == transfer.offset) { "Cannot seek partial download; choose Restart download" }
                            FileOutputStream(descriptor.fileDescriptor).use { output ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(65536); var last = 0L
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        if (downloads.record(id)?.optString("phase") != "Downloading") throw CancellationException("Download paused or removed")
                                        val n = input.read(buffer); if (n < 0) break
                                        bytes = Math.addExact(bytes, n.toLong())
                                        require(bytes <= (asset?.bytes ?: MAX_DOWNLOAD)) { "Download exceeds allowed size" }
                                        output.write(buffer, 0, n)
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - last > 1000) { last = now; downloads.progress(id) { it.put("bytes", bytes) }; publish("$title · ${bytes / 1_048_576} MiB") }
                                    }
                                    if (transfer.total >= 0) require(bytes == transfer.total) { "Download truncated: $bytes bytes, expected ${transfer.total}" }
                                    require(bytes > 0) { "Downloaded file is empty" }
                                    output.flush(); output.fd.sync()
                                    downloads.progress(id) { it.put("bytes", bytes).put("total", bytes) }
                                }
                            }
                        } ?: error("Cannot write to the selected download folder")
                    }
                } finally { cancel.cancel() }
            }
            if (asset != null) {
                downloads.progress(id) { it.put("phase", "Verifying") }
                publish("Verifying $title")
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                contentResolver.openInputStream(target)?.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (downloads.record(id)?.optString("phase") != "Verifying") throw CancellationException("Download paused or removed")
                        val n = input.read(buffer); if (n < 0) break
                        length += n; require(length <= asset.bytes) { "Downloaded artifact is too large" }
                        digest.update(buffer, 0, n)
                    }
                } ?: error("Cannot verify downloaded file")
                if (length != asset.bytes || digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != asset.sha256) {
                    downloads.progress(id) { it.put("restart", true) }
                    error("Downloaded model failed SHA-256 verification. Retry will download it again.")
                }
            }
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 29 && target.authority == MediaStore.AUTHORITY) {
                contentResolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            downloads.progress(id) { it.put("downloadComplete", true) }
            complete = true
        }
        if (complete && modelId.isNotBlank()) {
            currentCoroutineContext().ensureActive()
            DownloadStorage.requireSpace(this, asset, asset?.bytes ?: 0, record.optString("tree").isNotBlank())
            downloads.progress(id) { it.put("phase", "Installing") }
            publish("Installing ${record.getString("title")}")
            val installed = downloads.installModel(id, modelId)
            currentCoroutineContext().ensureActive()
            downloads.progress(id) {
                it.put("phase", if (installed.isBlank()) "Complete" else "Installed")
                    .put("installed", installed).remove("error")
            }
        } else downloads.progress(id) { it.put("phase", "Complete").remove("error") }
    }
    private fun createDestination(record: org.json.JSONObject): Uri {
        val subfolder = if (record.getBoolean("modelsFolder")) "models" else "files"
        val title = record.getString("title")
        val tree = record.optString("tree")
        if (tree.isNotBlank()) {
            val base = DocumentFile.fromTreeUri(this, Uri.parse(tree)) ?: error("Download folder is no longer available. Choose it again in Downloads.")
            check(base.canWrite()) { "Download folder permission was revoked. Choose it again in Downloads." }
            val folder = base.findFile(subfolder) ?: base.createDirectory(subfolder) ?: error("Cannot create $subfolder in the download folder")
            check(folder.isDirectory) { "$subfolder is not a folder" }
            val name = if (folder.findFile(title) == null) title else "${-record.getLong("id") }-$title"
            return folder.createFile("application/octet-stream", name)?.uri ?: error("Cannot create $title in the download folder")
        }
        check(Build.VERSION.SDK_INT >= 29) { "Choose a download folder in Downloads" }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, title)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Hearth/$subfolder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: error("Cannot create file in Downloads/Hearth")
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("page", 2), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Hearth").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun publish(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text)) }
    override fun onTimeout(startId: Int, fgsType: Int) { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
    companion object {
        @Volatile internal var running = false
        internal val jobs = ConcurrentHashMap<Long, Job>()
        private const val CHANNEL = "file-downloads"
        private const val NOTIFICATION = 302
        private const val MAX_DOWNLOAD = 16L * 1024 * 1024 * 1024
    }
}
