package com.sal7one.transiber.downloads

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.sal7one.transiber.MainActivity
import com.sal7one.hearth.core.downloads.Aria2Request
import com.sal7one.hearth.core.downloads.Aria2Transfer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Durable records own the queue. Network transfers never hold verification/install slots. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var downloads: FileDownloads
    private lateinit var settings: DownloadQueuePreferences
    private var drain: Job? = null
    private var lastStart = 0
    @Volatile private var queueChanged = true
    private val transferSlots = DownloadWorkSlots { settings.read().simultaneousFiles }
    // Atomic model installs are serialized: related multipart imports share their destination.
    private val preparationSlots = DownloadWorkSlots { 1 }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        running = true
        downloads = FileDownloads(applicationContext)
        settings = DownloadQueuePreferences(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Model and file downloads", NotificationManager.IMPORTANCE_LOW))
        downloads.recoverInterrupted()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        startNotification()
        transferSlots.refresh()
        queueChanged = true
        queueEvents.trySend(Unit)
        if (intent?.action == ACTION_PAUSE_ALL) {
            scope.launch {
                try { withContext(Dispatchers.IO) { downloads.pauseAll() } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION + 1,
                        notification("Could not pause downloads: ${e.message ?: e}", ongoing = false))
                } finally {
                    if (drain?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                }
            }
            return START_NOT_STICKY
        }
        if (drain?.isActive != true) drain = scope.launch {
            try {
                while (isActive) {
                    if (queueChanged) {
                        queueChanged = false
                        downloads.pendingIds().forEach { id ->
                        if (jobs.containsKey(id)) return@forEach
                        val task = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            try { runDownload(id) }
                            catch (e: CancellationException) {
                                if (downloads.record(id)?.optString("phase") !in setOf(null, "Paused", "Waiting"))
                                    downloads.update(id) { it.put("phase", "Paused").put("error", "Transfer interrupted. Resume continues saved bytes.") }
                                throw e
                            } catch (e: Exception) {
                                // Socket close after Pause is cancellation, even if Resume
                                // has already journaled the next attempt as Waiting.
                                currentCoroutineContext().ensureActive()
                                if (downloads.record(id)?.optString("phase") !in setOf(null, "Paused")) downloads.fail(id, e)
                            } finally {
                                if (downloads.record(id) == null) { stagingFile(id).delete(); File(stagingFile(id).path + ".aria2").delete() }
                            }
                        }
                        jobs[id] = task
                        task.invokeOnCompletion {
                            jobs.remove(id, task)
                            // A quick Pause → Resume can requeue an item while its old
                            // network call is still shutting down. Start its new generation.
                            if (downloads.record(id)?.optString("phase") == "Waiting") queueChanged = true
                            queueEvents.trySend(Unit)
                        }
                        task.start()
                        }
                    }
                    if (jobs.isEmpty()) {
                        if (downloads.pendingIds().isEmpty()) break
                        // A record can arrive before its service-start command (or
                        // that command can fail). Never park with unstarted work.
                        queueChanged = true
                        continue
                    }
                    queueEvents.receive()
                }
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStart) }
        }
        return START_REDELIVER_INTENT
    }
    private fun startNotification() {
        val notification = notification("Downloads continue in the background")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
    }
    private suspend fun runDownload(id: Long) {
        val record = downloads.record(id) ?: return
        if (record.optString("phase") != "Waiting") return
        val modelId = record.optString("model")
        com.sal7one.transiber.caption.SpeechAbiPolicy.requireCatalogModel(modelId)
        check(com.sal7one.transiber.byok.ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
        if (modelId.isNotBlank() || record.optBoolean("modelsFolder"))
            com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(this, "model downloads")
        if (com.sal7one.transiber.translation.Ct2Package.part(modelId) != null)
            com.sal7one.transiber.translation.Ct2Package.requireRuntime(this)
        if (modelId == DownloadAssets.MAGPIE_TOKENIZER) com.sal7one.transiber.voice.MagpiePolicy.requireAllowed(this, "Magpie tokenizer download")
        com.sal7one.transiber.voice.VoiceCatalog.find(modelId)?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(this, it.family, "speech model download") }
        val asset = DownloadAssets.find(modelId)
        val title = record.getString("title")
        if (record.optBoolean("downloadComplete")) {
            downloads.progress(id) { it.put("phase", "Verifying").put("preparingQueued", true) }
            preparationSlots.withSlot {
                verifySaved(id, asset)
                if (record.optString("previousPhase") == "Installed" && record.optString("installed").isNotBlank()) {
                    val health = downloads.inspect(id, checkOriginal = false)
                    check(health.installed == DownloadHealth.VERIFIED) {
                        "Installed model folder is missing or damaged. Original download is verified; reinstall from the downloaded file."
                    }
                    downloads.progress(id) { it.put("phase", "Installed"); it.remove("previousPhase"); it.remove("error") }
                } else install(id, modelId, asset)
            }
            return
        }
        var staged: File? = null
        var target: Uri? = null
        while (settings.read().unmeteredOnly && !unmetered()) {
            currentCoroutineContext().ensureActive()
            downloads.progress(id) { it.put("networkWait", true) }
            publish("Waiting for an unmetered network")
            delay(1500)
        }
        downloads.progress(id) { it.remove("networkWait") }
        transferSlots.withSlot {
            val policy = settings.read()
            val selection = if (asset != null && asset.ranges.isEmpty()) selectAria2Runtime(policy.backend) { Aria2Backend.prepare(this) }
                else Aria2RuntimeSelection(null)
            val runtime = selection.runtime
            downloads.progress(id) {
                it.remove("backendNotice")
                selection.notice?.let { notice -> it.put("backendNotice", notice) }
            }
            check(policy.backend != "aria2" || runtime != null || asset?.ranges?.isNotEmpty() == true) { "aria2 is unavailable for this device or file. Select HTTP or Automatic in download settings." }
            val started = SystemClock.elapsedRealtime()
            if (runtime != null) {
                val file = stagingFile(id)
                if (record.optBoolean("restart")) { file.delete(); File(file.path + ".aria2").delete() }
                val freshTransfer = !file.exists() && !File(file.path + ".aria2").exists()
                val pinned = checkNotNull(asset)
                // aria2 files can be sparse/preallocated: only a saved progress count
                // can reduce the storage reservation, never the staging file length.
                val budget = DownloadBudget.estimate(pinned.bytes, pinned.installedBytes,
                    if (freshTransfer) 0 else downloads.record(id)?.optLong("bytes", 0)?.coerceIn(0, pinned.bytes) ?: 0)
                val publicCopy = if (record.optString("tree").isBlank()) pinned.bytes else 0L
                val required = Math.addExact(Math.addExact(budget.remainingDownload, maxOf(budget.installation, publicCopy)), budget.reserve)
                check(DownloadStorage.available(this) >= required) { "Not enough private storage to stage, save and install this aria2 transfer. Select HTTP to write directly to your download folder." }
                downloads.progress(id) { it.put("phase", "Downloading").put("backend", "aria2").put("connections", policy.connectionsPerFile); it.remove("restart"); it.remove("error") }
                var last = 0L
                var firstProgress = -1L
                var finalProgress = 0L
                Aria2Transfer.download(Aria2Request(runtime.executable, file, record.getString("url"), pinned.bytes,
                    policy.connectionsPerFile, policy.bandwidthKiB * 1024L, policy.retries, runtime.environment, httpOnlyBuild = true)) { progress ->
                    if (downloads.record(id)?.optString("phase") != "Downloading") throw CancellationException("Download paused or removed")
                    if (policy.unmeteredOnly && !unmetered()) throw IOException("Unmetered connection lost; resume when connected")
                    if (firstProgress < 0) firstProgress = progress.downloadedBytes
                    finalProgress = progress.downloadedBytes
                    val now = SystemClock.elapsedRealtime()
                    if (now - last >= 500 || progress.downloadedBytes == progress.totalBytes) {
                        last = now
                        downloads.progress(id) { it.put("bytes", progress.downloadedBytes).put("total", progress.totalBytes).put("bytesPerSecond", progress.bytesPerSecond) }
                        publish("Downloading $title")
                    }
                }
                downloads.progress(id) { it.put("transferredBytes", if (freshTransfer) pinned.bytes else (finalProgress - firstProgress.coerceAtLeast(0)).coerceAtLeast(0)) }
                staged = file
            } else {
                if (record.optString("backend") == "aria2" && record.optString("uri").isNotBlank()) {
                    downloads.progress(id) { it.put("restart", true) }
                }
                downloads.progress(id) { it.put("backend", if (asset?.ranges?.isNotEmpty() == true) "http-ranges" else "http").put("connections", 1) }
                var attempt = 0
                var observedNetworkBytes = 0L
                val countNetworkBytes: (Int) -> Unit = { count -> observedNetworkBytes = Math.addExact(observedNetworkBytes, count.toLong()) }
                downloads.progress(id) { it.put("transferredBytes", 0).put("retries", 0) }
                while (true) {
                    try { target = if (asset?.ranges?.isNotEmpty() == true) transferRanges(id, asset, policy, countNetworkBytes) else transferHttp(id, asset, policy, countNetworkBytes); break }
                    catch (e: CancellationException) { throw e }
                    catch (e: IOException) {
                        if (attempt >= policy.retries) throw e
                        attempt++
                        downloads.progress(id) { it.put("retries", attempt).put("retryMessage", e.message ?: e.toString()) }
                        delay((1000L shl (attempt - 1).coerceAtMost(4)))
                    }
                }
                downloads.progress(id) { it.put("transferredBytes", observedNetworkBytes) }
            }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            downloads.progress(id) { it.put("transferMillis", elapsed).put("phase", "Verifying").put("preparingQueued", true).remove("retryMessage") }
        }
        // Release the network slot before hashing, copying SAF files or unpacking models.
        preparationSlots.withSlot {
            publish("Verifying $title")
            val actual = (staged?.inputStream() ?: contentResolver.openInputStream(checkNotNull(target)))?.use { input ->
                fingerprint(id, input, asset?.bytes ?: downloads.record(id)?.optLong("total", MAX_DOWNLOAD)?.takeIf { it > 0 } ?: MAX_DOWNLOAD)
            } ?: error("Cannot verify downloaded file")
            if (asset != null && actual != DownloadIntegrity.Fingerprint(asset.bytes, asset.sha256)) {
                downloads.progress(id) { it.put("restart", true) }
                error("Downloaded model failed SHA-256 verification. Restart will download it again.")
            }
            staged?.let { file -> target = exportStaged(id, file, actual.bytes) }
            val destination = checkNotNull(target)
            if (Build.VERSION.SDK_INT >= 29 && destination.authority == MediaStore.AUTHORITY)
                contentResolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            downloads.progress(id) { it.put("downloadComplete", true).put("sha256", actual.sha256).put("verifiedBytes", actual.bytes).put("uri", destination.toString()) }
            staged?.delete()
            staged?.let { File(it.path + ".aria2").delete() }
            if (staged == null) {
                // HTTP must never interpret aria2's sparse pieces as a contiguous prefix.
                // Retain those pieces until the replacement payload is actually verified.
                stagingFile(id).delete(); File(stagingFile(id).path + ".aria2").delete()
            }
            install(id, modelId, asset)
        }
    }
    private suspend fun verifySaved(id: Long, asset: DownloadAsset?) {
        val record = downloads.record(id) ?: throw CancellationException("Download removed")
        downloads.progress(id) { it.put("phase", "Verifying") }
        publish("Verifying ${record.getString("title")}")
        val expected = asset?.let { DownloadIntegrity.Fingerprint(it.bytes, it.sha256) }
            ?: record.optString("sha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?.let { DownloadIntegrity.Fingerprint(record.optLong("verifiedBytes", -1), it) }
            ?: error("This older download has no saved checksum. Choose Restart download.")
        val actual = record.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)?.let { uri ->
            contentResolver.openInputStream(uri)?.use { input -> fingerprint(id, input, expected.bytes.coerceAtLeast(1)) }
        }
        check(actual == expected) { "Saved download is missing or failed SHA-256 verification. Choose Restart download." }
    }
    private suspend fun fingerprint(id: Long, input: java.io.InputStream, bytes: Long): DownloadIntegrity.Fingerprint {
        val job = currentCoroutineContext()
        downloads.progress(id) { it.put("phase", "Verifying").put("preparingQueued", false).put("preparationAction", "hash")
            .put("verificationBytes", 0).put("verificationTotal", bytes) }
        var last = 0L
        val actual = DownloadIntegrity.fingerprintProgress(input, bytes, { done ->
            val now = SystemClock.elapsedRealtime()
            if (now - last >= 500 || done == bytes) { last = now; downloads.progress(id) { it.put("verificationBytes", done) } }
        }) {
            job.ensureActive()
            if (downloads.record(id)?.optString("phase") != "Verifying") throw CancellationException("Download paused or removed")
        }
        downloads.progress(id) { it.put("verificationBytes", actual.bytes) }
        return actual
    }
    private suspend fun install(id: Long, modelId: String, asset: DownloadAsset?) {
        currentCoroutineContext().ensureActive()
        val record = downloads.record(id) ?: throw CancellationException("Download removed")
        if (modelId.isNotBlank()) {
            DownloadStorage.requireSpace(this, asset, asset?.bytes ?: 0, record.optString("tree").isNotBlank())
            downloads.progress(id) { it.put("phase", "Installing") }
            publish("Installing ${record.getString("title")}")
            val installed = downloads.installModel(id, modelId)
            currentCoroutineContext().ensureActive()
            downloads.progress(id) {
                it.put("phase", if (installed.isBlank()) "Complete" else "Installed").put("installed", installed)
                it.remove("previousPhase"); it.remove("error")
            }
            com.sal7one.transiber.models.ModelInstallRefresh.schedule(applicationContext) { message ->
                downloads.update(id) { it.put("diagnosticError", message) }
            }
        } else downloads.progress(id) { it.put("phase", "Complete"); it.remove("previousPhase"); it.remove("error") }
    }
    private suspend fun transferHttp(id: Long, asset: DownloadAsset?, policy: DownloadQueuePolicy, countNetworkBytes: (Int) -> Unit): Uri {
        val record = downloads.record(id) ?: throw CancellationException("Download removed")
        var destination = record.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)
        var offset = 0L
        if (destination != null && !record.optBoolean("restart")) {
            offset = runCatching { contentResolver.openFileDescriptor(destination!!, "rw")?.use {
                Os.lseek(it.fileDescriptor, 0, OsConstants.SEEK_END).also { size -> require(size >= 0) }
            } ?: 0L }.getOrElse { error("This folder cannot resume the partial download. Choose Restart download.") }
        }
        if (record.optBoolean("restart") || (asset == null && offset > 0) || (asset != null && offset > asset.bytes)) {
            destination?.let { contentResolver.delete(it, null, null) }; destination = null; offset = 0
        }
        if (destination == null) destination = createDestination(record)
        val target = checkNotNull(destination)
        DownloadStorage.requireSpace(this, asset, offset, record.optString("tree").isNotBlank())
        downloads.progress(id) { it.put("uri", target.toString()).put("phase", "Downloading").put("bytes", offset); it.remove("restart"); it.remove("error") }
        if (asset != null && offset == asset.bytes) return target
        val checked = DownloadSpec.parse(record.getString("url"), record.getString("title"))
        val validator = record.optString("validator").takeIf(String::isNotBlank)
        val request = Request.Builder().url(checked.url).header("Accept-Encoding", "identity")
        if (offset > 0) { request.header("Range", "bytes=$offset-"); validator?.let { request.header("If-Range", it) } }
        val call = client.newCall(request.build())
        val cancel = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code in setOf(408, 429) || response.code in 500..599) throw IOException("HTTP ${response.code} ${response.message}")
                check(response.isSuccessful) { "HTTP ${response.code} ${response.message}" }
                val body = response.body ?: error("Download response has no body")
                val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified")
                val transfer = DownloadResume.response(response.code, offset, response.header("Content-Range"), body.contentLength(), asset?.bytes, validator, responseValidator)
                var bytes = transfer.offset
                downloads.progress(id) { it.put("bytes", bytes).put("total", transfer.total)
                    if (responseValidator != null) it.put("validator", responseValidator) else it.remove("validator") }
                contentResolver.openFileDescriptor(target, if (transfer.offset == 0L) "rwt" else "rw")?.use { descriptor ->
                    if (transfer.offset > 0) check(Os.lseek(descriptor.fileDescriptor, transfer.offset, OsConstants.SEEK_SET) == transfer.offset) { "Cannot seek partial download; choose Restart download" }
                    FileOutputStream(descriptor.fileDescriptor).use { output -> body.byteStream().use { input ->
                        val buffer = ByteArray(65536)
                        val began = SystemClock.elapsedRealtime()
                        var last = began
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (downloads.record(id)?.optString("phase") != "Downloading") throw CancellationException("Download paused or removed")
                            if (policy.unmeteredOnly && !unmetered()) throw IOException("Unmetered connection lost")
                            val n = input.read(buffer); if (n < 0) break
                            countNetworkBytes(n)
                            bytes = Math.addExact(bytes, n.toLong())
                            require(bytes <= (asset?.bytes ?: MAX_DOWNLOAD)) { "Download exceeds allowed size" }
                            output.write(buffer, 0, n)
                            val elapsed = (SystemClock.elapsedRealtime() - began).coerceAtLeast(1)
                            if (policy.bandwidthKiB > 0) {
                                val expected = (bytes - transfer.offset) * 1000 / (policy.bandwidthKiB * 1024L)
                                if (expected > elapsed) delay(expected - elapsed)
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - last >= 500) {
                                last = now
                                downloads.progress(id) { it.put("bytes", bytes).put("bytesPerSecond", (bytes - transfer.offset) * 1000 / (now - began).coerceAtLeast(1)) }
                                publish("Downloading ${record.getString("title")}")
                            }
                        }
                        if (transfer.total >= 0 && bytes != transfer.total) throw IOException("Download truncated: $bytes bytes, expected ${transfer.total}")
                        require(bytes > 0) { "Downloaded file is empty" }
                        output.flush(); output.fd.sync()
                        downloads.progress(id) { it.put("bytes", bytes).put("total", bytes).put("transferredBytes", it.optLong("transferredBytes") + bytes - transfer.offset) }
                    } }
                } ?: error("Cannot write to the selected download folder")
            }
        } finally { cancel.cancel() }
        return target
    }
    private suspend fun transferRanges(id: Long, asset: DownloadAsset, policy: DownloadQueuePolicy, countNetworkBytes: (Int) -> Unit): Uri {
        val record = downloads.record(id) ?: throw CancellationException("Download removed")
        val sourceBytes = checkNotNull(asset.sourceBytes) { "Pinned range source length is missing" }
        check(asset.ranges.sumOf { Math.addExact(it.last - it.first, 1) } == asset.bytes) { "Pinned range payload length is inconsistent" }
        var destination = record.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)
        var offset = if (destination != null && !record.optBoolean("restart")) {
            runCatching { contentResolver.openFileDescriptor(destination!!, "rw")?.use { Os.lseek(it.fileDescriptor, 0, OsConstants.SEEK_END) } ?: 0L }
                .getOrElse { error("This folder cannot resume tokenizer ranges. Choose Restart download.") }
        } else 0L
        if (record.optBoolean("restart") || offset !in 0..asset.bytes) {
            destination?.let { contentResolver.delete(it, null, null) }; destination = null; offset = 0
        }
        if (destination == null) destination = createDestination(record)
        val target = checkNotNull(destination)
        DownloadStorage.requireSpace(this, asset, offset, record.optString("tree").isNotBlank())
        downloads.progress(id) { it.put("uri", target.toString()).put("phase", "Downloading").put("bytes", offset).put("total", asset.bytes); it.remove("restart"); it.remove("error") }
        val started = SystemClock.elapsedRealtime()
        val initial = offset
        val previousTransferred = record.optLong("transferredBytes")
        var last = 0L
        contentResolver.openFileDescriptor(target, if (offset == 0L) "rwt" else "rw")?.use { descriptor ->
            if (offset > 0) check(Os.lseek(descriptor.fileDescriptor, offset, OsConstants.SEEK_SET) == offset) { "Cannot seek saved tokenizer ranges" }
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                for (slice in DownloadRangeMap.remaining(asset.ranges, offset, sourceBytes)) {
                    currentCoroutineContext().ensureActive()
                    val call = client.newCall(Request.Builder().url(asset.url).header("Accept-Encoding", "identity")
                        .header("Range", "bytes=${slice.sourceFirst}-${slice.sourceLast}").build())
                    val cancel = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                    try {
                        call.execute().use { response ->
                            if (response.code in setOf(408, 429) || response.code in 500..599) throw IOException("HTTP ${response.code} ${response.message}")
                            check(response.code == 206) { "Model host must honor the pinned tokenizer Range request; HTTP ${response.code}. Refusing to download the full archive." }
                            check(response.header("Content-Range") == "bytes ${slice.sourceFirst}-${slice.sourceLast}/$sourceBytes") { "Model host returned the wrong tokenizer source range" }
                            val body = response.body ?: error("Tokenizer response has no body")
                            check(body.contentLength() == slice.bytes) { "Tokenizer range length differs from its pin" }
                            body.byteStream().use { input ->
                                val buffer = ByteArray(65536); var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    if (downloads.record(id)?.optString("phase") != "Downloading") throw CancellationException("Download paused or removed")
                                    if (policy.unmeteredOnly && !unmetered()) throw IOException("Unmetered connection lost")
                                    val n = input.read(buffer); if (n < 0) break
                                    countNetworkBytes(n)
                                    copied = Math.addExact(copied, n.toLong()); check(copied <= slice.bytes) { "Tokenizer response exceeds the pinned range" }
                                    output.write(buffer, 0, n); offset = Math.addExact(offset, n.toLong())
                                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                                    if (policy.bandwidthKiB > 0) {
                                        val expected = (offset - initial) * 1000 / (policy.bandwidthKiB * 1024L)
                                        if (expected > elapsed) delay(expected - elapsed)
                                    }
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - last >= 500) { last = now; downloads.progress(id) {
                                        it.put("bytes", offset).put("transferredBytes", previousTransferred + offset - initial)
                                            .put("bytesPerSecond", (offset - initial) * 1000 / elapsed)
                                    }; publish("Downloading Magpie tokenizer") }
                                }
                                if (copied != slice.bytes) throw IOException("Tokenizer range was truncated: $copied / ${slice.bytes}")
                            }
                        }
                    } finally { cancel.cancel() }
                }
                output.flush(); output.fd.sync()
            }
        } ?: error("Cannot write tokenizer ranges to the chosen folder")
        check(offset == asset.bytes) { "Tokenizer ranges are incomplete" }
        downloads.progress(id) { it.put("bytes", offset).put("transferredBytes", previousTransferred + offset - initial) }
        return target
    }
    private fun stagingFile(id: Long): File {
        val directory = File(filesDir, "download-staging").apply { check(isDirectory || mkdirs()) { "Cannot create download staging folder" } }
        check(directory.canonicalFile == File(filesDir.canonicalFile, "download-staging")) { "Unsafe download staging folder" }
        val file = File(directory.canonicalFile, "$id.part")
        check(file.canonicalFile == file.absoluteFile) { "Unsafe partial download path" }
        return file
    }
    private suspend fun exportStaged(id: Long, file: File, bytes: Long): Uri {
        val record = downloads.record(id) ?: throw CancellationException("Download removed")
        val target = record.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse) ?: createDestination(record)
        downloads.progress(id) { it.put("uri", target.toString()).put("preparationAction", "export").put("verificationBytes", 0).put("verificationTotal", bytes) }
        publish("Saving ${record.getString("title")}")
        contentResolver.openFileDescriptor(target, "rwt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(65536); var copied = 0L; var last = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); copied += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - last >= 500 || copied == bytes) { last = now; downloads.progress(id) { it.put("verificationBytes", copied) } }
                }
                check(copied == bytes) { "Downloaded file changed while saving" }; output.flush(); output.fd.sync()
            } }
        } ?: error("Cannot save to the selected download folder. Choose it again in Downloads.")
        return target
    }
    private fun unmetered(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !manager.isActiveNetworkMetered
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
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("page", 2), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Hearth downloads").setContentText(text).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true)
            .setSubText("${jobs.size} queued or preparing · ${settings.read().simultaneousFiles} simultaneous files")
        if (ongoing) builder.addAction(0, "Pause all", pause) else builder.setAutoCancel(true)
        return builder.build()
    }
    private fun publish(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text)) }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits background dataSync time. Preserve the queue and
        // stop promptly; bringing Hearth forward lets the owner resume it.
        val message = try {
            downloads.pauseAll()
            "Android paused long-running background downloads. Open Hearth to resume saved files."
        } catch (error: Exception) { "Android stopped background downloads. Queue save failed: ${error.message ?: error}" }
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION + 1, notification(message, ongoing = false)) }
        finally { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
    companion object {
        @Volatile internal var running = false
        internal val jobs = ConcurrentHashMap<Long, Job>()
        private val queueEvents = Channel<Unit>(Channel.CONFLATED)
        private const val CHANNEL = "file-downloads"
        private const val NOTIFICATION = 302
        private const val ACTION_PAUSE_ALL = "com.sal7one.transiber.downloads.PAUSE_ALL"
        private const val MAX_DOWNLOAD = 16L * 1024 * 1024 * 1024
    }
}
