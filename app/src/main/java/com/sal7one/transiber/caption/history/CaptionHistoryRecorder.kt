package com.sal7one.transiber.caption.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** A bounded text-only IO queue. Never blocks the recorder, poller or native inference.
 * Queue/storage failure stops saving and is visible; speech itself keeps running.
 */
internal class CaptionHistoryRecorder(
    scope: CoroutineScope,
    private val write: suspend (CaptionHistoryWrite, () -> Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val capture: CaptionHistoryCapture = CaptionHistoryCapture(),
) {
    private val queue = Channel<CaptionHistoryWrite>(64)
    private var failed = false
    private var closed = false
    @Volatile var error: String? = null
        private set
    val job = scope.launch {
        for (item in queue) {
            if (!capture.mayWrite(item)) continue
            try { write(item) { capture.mayWrite(item) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message ?: e.toString()) }
        }
    }
    @Synchronized fun setEnabled(enabled: Boolean) {
        if (closed) return
        // A deliberate off/on toggle retries a storage failure.
        if (!enabled) { failed = false; error = null }
        capture.setEnabled(enabled && !failed)
    }
    fun boundary() = capture.boundary()
    fun observe(line: SavedCaption, complete: Boolean = true) = offer(capture.observe(line, complete))
    fun translation(id: Long, text: String, language: String?) = offer(capture.translation(id, text, language))
    @Synchronized private fun offer(item: CaptionHistoryWrite?) {
        if (closed || item == null) return
        try { item.line.validate() }
        catch (e: IllegalArgumentException) { fail(e.message ?: e.toString()); return }
        if (queue.trySend(item).isFailure) fail("Caption history queue is full. Saving stopped; turn history off and on to retry.")
    }
    @Synchronized private fun fail(message: String) {
        if (failed) return
        failed = true
        error = message
        capture.setEnabled(false)
        onError(message)
    }
    @Synchronized fun close() { closed = true; capture.close(); queue.close() }
}
