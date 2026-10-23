package com.sal7one.transiber.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

internal data class ModelImportOutcome(val source: String, val label: String, val result: String = "", val error: String = "")
internal data class ModelImportBatch(
    val owner: String, val active: Boolean = false, val requested: Int = 0,
    val current: Int = 0, val currentFile: String = "", val bytes: Long = 0, val totalBytes: Long = -1,
    val outcomes: List<ModelImportOutcome> = emptyList(), val cancelled: Boolean = false, val revision: Long = 0,
)

/** An import belongs to the application, so leaving a settings sheet does not discard work.
 * Each file retains its own failure. Runtime copies still use each model family's atomic,
 * pinned import implementation; the source name is a display hint, never authentication.
 */
internal object ModelImportQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publication = Mutex()
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val mutable = MutableStateFlow<Map<String, ModelImportBatch>>(emptyMap())
    val state: StateFlow<Map<String, ModelImportBatch>> = mutable.asStateFlow()
    private fun update(owner: String, change: (ModelImportBatch) -> ModelImportBatch) = synchronized(lock) {
        val previous = mutable.value[owner] ?: ModelImportBatch(owner)
        mutable.value = mutable.value + (owner to change(previous))
    }
    fun submit(context: Context, owner: String, uris: List<Uri>, importFile: suspend (ModelImportSource) -> String) {
        require(uris.isNotEmpty()) { "Choose at least one model file" }
        val app = context.applicationContext
        val selected = uris.distinct()
        synchronized(lock) {
            check(jobs[owner] == null) { "An import batch is already running here" }
            update(owner) { ModelImportBatch(owner, active = true, requested = selected.size, revision = it.revision + 1) }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    publication.withLock {
                        selected.forEachIndexed { index, uri ->
                            ensureActive()
                            var name = uri.lastPathSegment ?: "Model file ${index + 1}"
                            try {
                                val source = ModelImportSource(app, uri, currentCoroutineContext()) { label, bytes, total ->
                                    update(owner) { it.copy(current = index + 1, currentFile = label, bytes = bytes, totalBytes = total) }
                                }
                                name = source.displayName
                                // Some providers support only transient grants. The verified private
                                // copy remains usable after the original grant expires.
                                try { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                catch (_: SecurityException) { }
                                update(owner) { it.copy(current = index + 1, currentFile = name, bytes = 0, totalBytes = source.size) }
                                val result = importFile(source)
                                ModelInstallRefresh.schedule(app)
                                ensureActive()
                                update(owner) { it.copy(outcomes = it.outcomes + ModelImportOutcome(uri.toString(), name, result = result), revision = it.revision + 1) }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                ensureActive()
                                val message = generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() }
                                update(owner) { it.copy(outcomes = it.outcomes + ModelImportOutcome(uri.toString(), name, error = message), revision = it.revision + 1) }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    update(owner) { it.copy(cancelled = true) }
                    throw e
                } finally { update(owner) { it.copy(active = false, revision = it.revision + 1) } }
            }
            jobs[owner] = job
            job.invokeOnCompletion { synchronized(lock) { if (jobs[owner] === job) jobs.remove(owner) } }
            job.start()
        }
    }
    fun cancel(owner: String) = synchronized(lock) { jobs[owner]?.cancel() }
}

internal class ModelImportSource(private val context: Context, val uri: Uri, private val job: CoroutineContext,
    private val progress: (String, Long, Long) -> Unit) {
    val displayName: String
    val size: Long
    init {
        var label = uri.lastPathSegment ?: "Model file"
        var bytes = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) label = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) bytes = cursor.getLong(it) }
            }
        }
        displayName = label
        size = bytes
    }
    fun checkActive() = job.ensureActive()
    fun open(): InputStream {
        checkActive()
        val source = context.contentResolver.openInputStream(uri) ?: error("Cannot open $displayName")
        var copied = 0L
        var last = 0L
        fun counted(count: Int) {
            if (count > 0) copied = Math.addExact(copied, count.toLong())
            val now = System.nanoTime()
            if (count < 0 || now - last >= 200_000_000) { last = now; progress(displayName, copied, size) }
        }
        return object : FilterInputStream(source) {
            override fun read(): Int { checkActive(); return source.read().also { counted(if (it < 0) -1 else 1) } }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkActive(); return source.read(buffer, offset, length).also(::counted)
            }
            override fun skip(count: Long): Long {
                // Keep progress/cancellation accurate even for a decoder that skips bytes.
                checkActive(); val skipped = source.skip(count); copied = Math.addExact(copied, skipped); progress(displayName, copied, size); return skipped
            }
        }
    }
}
