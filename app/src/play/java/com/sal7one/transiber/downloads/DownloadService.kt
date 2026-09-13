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
                        catch (e: CancellationException) { if (downloads.record(id) != null) downloads.fail(id, IllegalStateException("Download interrupted. Tap Retry to continue.")); throw e }
                        catch (e: Exception) { downloads.fail(id, e) }
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
        val modelId = record.optString("model")
        var complete = record.optBoolean("downloadComplete")
        if (!complete) {
            // An interrupted partial is replaced only on explicit retry or service restart.
            record.optString("uri").takeIf(String::isNotBlank)?.let { contentResolver.delete(Uri.parse(it), null, null) }
            val destination = createDestination(record)
            downloads.update(id) { it.put("uri", destination.toString()).put("phase", "Downloading").put("bytes", 0).remove("error") }
            val title = record.getString("title")
            publish("Downloading $title")
            val checked = DownloadSpec.parse(record.getString("url"), title)
            val call = client.newCall(Request.Builder().url(checked.url).build())
            val cancel = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code} ${response.message}" }
                    val expected = com.sal7one.transiber.voice.VoiceCatalog.find(modelId)?.bytes ?: OcrCatalog.find(modelId)?.bytes ?: SpeechDownloads.find(modelId)?.bytes ?: TranslationCatalog.models.firstOrNull { it.id == modelId }?.bytes
                    val length = response.body?.contentLength() ?: -1
                    if (expected != null && length >= 0) require(length == expected) { "Download size differs from the selected model: $length, expected $expected" }
                    val total = expected ?: length
                    downloads.update(id) { it.put("total", total) }
                    val body = response.body ?: error("Download response has no body")
                    contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(65536); var bytes = 0L; var last = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(downloads.record(id) != null) { "Download removed" }
                                val n = input.read(buffer); if (n < 0) break
                                bytes += n
                                require(bytes <= (expected ?: MAX_DOWNLOAD)) { "Download exceeds allowed size" }
                                output.write(buffer, 0, n)
                                val now = SystemClock.elapsedRealtime()
                                if (now - last > 1000) { last = now; downloads.update(id) { it.put("bytes", bytes) }; publish("$title · ${bytes / 1_048_576} MiB") }
                            }
                            if (total >= 0) require(bytes == total) { "Download truncated: $bytes bytes, expected $total" }
                            require(bytes > 0) { "Downloaded file is empty" }
                            output.flush()
                            downloads.update(id) { it.put("bytes", bytes).put("total", bytes) }
                        }
                    } ?: error("Cannot write to the selected download folder")
                }
                if (Build.VERSION.SDK_INT >= 29 && destination.authority == MediaStore.AUTHORITY) {
                    contentResolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
                downloads.update(id) { it.put("downloadComplete", true) }
                complete = true
            } finally { cancel.cancel() }
        }
        if (complete && modelId.isNotBlank()) {
            downloads.update(id) { it.put("phase", "Installing") }
            publish("Installing ${record.getString("title")}")
            val installed = downloads.installModel(id, modelId)
            downloads.update(id) { it.put("phase", "Installed").put("installed", installed).remove("error") }
        } else downloads.update(id) { it.put("phase", "Complete").remove("error") }
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
