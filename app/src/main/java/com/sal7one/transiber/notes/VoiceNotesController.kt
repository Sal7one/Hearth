package com.sal7one.transiber.notes

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.sal7one.common_jni.audio.Pcm16WaveWriter
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.caption.history.CaptionHistoryRecorder
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class VoiceNotesState(val notes: List<VoiceNote> = emptyList(), val active: VoiceNote? = null,
    val busy: Boolean = false, val recording: Boolean = false, val partial: String = "",
    val detail: VoiceNote? = null, val usedBytes: Long = 0, val error: String? = null)

/** Owns one microphone and optionally the existing native/cloud speech pipeline. Audio recording
 * itself needs no model, network, login or overlay permission. Lifecycle Stop saves the partial WAV.
 */
internal class VoiceNotesController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val work = VoiceNoteWork()
    private val io = work.metadata
    private val store = VoiceNoteStore(context)
    private val _state = MutableStateFlow(VoiceNotesState())
    val state: StateFlow<VoiceNotesState> = _state.asStateFlow()
    private var operation: Job? = null
    private var finish = CompletableDeferred<Unit>()
    private var closing = false
    init { operation = scope.launch { reload(recover = true) }; operation?.invokeOnCompletion { operation = null } }
    private suspend fun reload(recover: Boolean = false) {
        try {
            val (notes, bytes) = withContext(io) { (if (recover) store.recoverInterrupted() else store.list(previewsOnly = true)) to store.usedBytes() }
            _state.update { it.copy(notes = notes, usedBytes = bytes) }
        } catch (e: Exception) { report(e.message ?: e.javaClass.simpleName) }
    }
    fun report(message: String) { _state.update { it.copy(error = message) } }
    fun stop() { finish.complete(Unit) }
    fun start(title: String, config: CaptionOverlayConfig, transcribe: Boolean, translate: Boolean) {
        if (closing || operation?.isActive == true || _state.value.busy) return
        if (CaptionCaptureService.running.value) { report("Stop live captions before recording a voice note"); return }
        val draft = VoiceNote(title = title.trim().ifBlank { "Voice note" }.take(200))
        finish = CompletableDeferred()
        _state.update { it.copy(active = draft, busy = true, error = null) }
        operation = scope.launch {
            var controller: CaptionEngineController? = null
            var history: CaptionHistoryRecorder? = null
            var observation: Job? = null
            var lease: AutoCloseable? = null
            var samples = 0L
            var failure: String? = null
            var captureCompleted = false
            val transcript = NoteTranscript()
            try {
                lease = LocalWorkGate.acquire("Voice notes")
                if (transcribe) {
                    controller = CaptionEngineController(context)
                    history = CaptionHistoryRecorder(this, write = { event, allowed ->
                        if (allowed()) {
                            val lines = transcript.observe(event.line)
                            _state.update { it.copy(active = it.active?.copy(lines = lines)) }
                            withContext(io) { store.save(draft.copy(samples = _state.value.active?.samples ?: 0, lines = lines)) }
                        }
                    }, onError = { message -> failure = message; report(message); finish.complete(Unit) })
                    history.setEnabled(true)
                    controller.historyRecorder = history
                    val engine = controller
                    observation = launch { engine.state.collect { update ->
                        _state.update { it.copy(partial = update.partial) }
                        if (update.status == CaptionEngineController.Status.ERROR) {
                            failure = update.error ?: "Speech recognition failed"; finish.complete(Unit)
                        }
                        // MT failure leaves both the recording and available original captions usable.
                        update.translationNotice?.let(::report)
                    } }
                    val ready = CompletableDeferred<Boolean>()
                    engine.start(config.forVoiceNote(translate)) { ready.complete(it) }
                    check(ready.await()) { engine.state.value.error ?: "Speech recognizer could not start" }
                }
                val maxSamples = withContext(io) {
                    store.capacitySamples().also { store.save(draft) }
                }
                withContext(work.capture) {
                    capture(draft, maxSamples, controller) { count ->
                        samples = count
                        _state.update { it.copy(active = it.active?.copy(samples = count), recording = true) }
                    }
                }
                captureCompleted = true
            } catch (e: CancellationException) {
                failure = "Recording interrupted" // close requests a graceful finish; cancellation is exceptional.
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                failure = message
                report(message)
            } catch (e: LinkageError) {
                val message = e.message ?: e.javaClass.simpleName
                failure = message
                report(message)
            } finally {
                withContext(NonCancellable) {
                    _state.update { it.copy(recording = false) }
                    controller?.let { engine ->
                        try {
                            val drainFailure = finishVoiceNoteSpeech(engine::stop) { try { engine.shutdown() } finally { engine.awaitReleased() } }
                            if (drainFailure != null) {
                                failure = "Voice note speech drain: ${drainFailure.message ?: drainFailure.javaClass.simpleName}"
                                report(checkNotNull(failure))
                            }
                        } catch (e: Exception) {
                            failure = e.message ?: e.javaClass.simpleName; report(checkNotNull(failure))
                        } catch (e: LinkageError) {
                            failure = e.message ?: e.javaClass.simpleName; report(checkNotNull(failure))
                        } finally { engine.historyRecorder = null }
                    }
                    observation?.cancelAndJoin()
                    history?.close(); history?.job?.join()
                    lease?.close()
                    withContext(io) {
                        try {
                            val file = store.audio(draft.id)
                            if (file.exists()) {
                                // If disk write/close failed, do not label an invalid WAV saved.
                                samples = Pcm16WaveWriter.recoverInterrupted(file, 16000, VoiceNoteStore.MAX_SAMPLES)
                                store.save(draft.copy(samples = samples, lines = transcript.snapshot(),
                                    status = if (captureCompleted && failure == null) NoteStatus.SAVED else NoteStatus.INTERRUPTED,
                                    error = failure?.take(4096)))
                            }
                        } catch (e: Exception) { report(e.message ?: e.javaClass.simpleName) }
                    }
                    reload()
                    _state.update { it.copy(busy = false, active = null, partial = "") }
                }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun capture(note: VoiceNote, maximum: Long, engine: CaptionEngineController?, progress: (Long) -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "AudioRecord.getMinBufferSize returned $minimum" }
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum, 6400)).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            Pcm16WaveWriter(store.audio(note.id), 16000, maximum).use { wave ->
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                val buffer = ShortArray(320)
                var nextUpdate = 0L
                while (!finish.isCompleted && currentCoroutineContext().isActive) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(count >= 0) { "AudioRecord.read returned $count" }
                    if (count == 0) { delay(5); continue }
                    wave.write(buffer, count)
                    engine?.pushAudio(buffer.copyOf(count), count)
                    if (wave.samplesWritten >= nextUpdate) { progress(wave.samplesWritten); nextUpdate = wave.samplesWritten + 16000 }
                }
                progress(wave.samplesWritten)
            }
        } finally {
            try { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            finally { record.release() }
        }
    }
    fun rename(note: VoiceNote, title: String) = mutate { store.save(store.load(note.id).copy(title = title.trim().ifBlank { note.title }.take(200))) }
    fun delete(note: VoiceNote) = mutate { store.delete(note.id) }
    private fun mutate(block: () -> Unit) {
        if (_state.value.busy || closing) return
        scope.launch {
            try { withContext(io) { block() }; reload() }
            catch (e: Exception) { report(e.message ?: e.javaClass.simpleName) }
        }
    }
    fun open(note: VoiceNote) {
        scope.launch { try {
            val complete = withContext(io) { store.load(note.id) }
            _state.update { it.copy(detail = complete) }
        } catch (e: Exception) { report(e.message ?: e.javaClass.simpleName) } }
    }
    suspend fun complete(note: VoiceNote): VoiceNote = withContext(io) { store.load(note.id) }
    fun audio(note: VoiceNote) = store.audio(note.id)
    override fun close() {
        closing = true; stop()
        scope.launch { operation?.join(); scope.cancel() }
    }
}
