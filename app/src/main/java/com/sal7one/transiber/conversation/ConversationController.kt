package com.sal7one.transiber.conversation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.common_jni.translation.LocalTranslationSession
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.coroutines.resume

internal data class ConversationState(
    val session: ConversationSession = ConversationSession(),
    val history: List<ConversationSession> = emptyList(),
    val status: String = "Ready",
    val activeSpeaker: Int? = null,
    val partial: String = "",
    val busy: Boolean = false,
    val listening: Boolean = false,
    val error: String? = null,
    val saveHistory: Boolean = true,
)

/** Screen-owned microphone sessions; never creates an overlay or changes caption preferences. */
internal class ConversationController(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val disk = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val store = ConversationStore(context)
    private val prefs = context.getSharedPreferences("conversation-options", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ConversationState(saveHistory = prefs.getBoolean("save", true)))
    val state: StateFlow<ConversationState> = _state.asStateFlow()
    private var engine: CaptionEngineController? = null
    private var recorder: AudioRecord? = null
    private var recording: Job? = null
    private var operation: Job? = null
    @Volatile private var translator: CancellableTextTranslator? = null
    private var finish = CompletableDeferred<Unit>()
    private var generation = 0L
    var onTranslation: ((ConversationTurn) -> Unit)? = null

    init {
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.list() }
                if (!_state.value.busy && _state.value.session.turns.isEmpty()) _state.update { it.copy(
                    history = saved, session = saved.firstOrNull() ?: it.session.copy(
                        first = prefs.getString("first", "ar") ?: "ar", second = prefs.getString("second", "en") ?: "en")) }
            } catch (e: Exception) { _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun languages(first: String, second: String) {
        if (_state.value.busy) return
        prefs.edit().putString("first", first).putString("second", second).apply()
        _state.update { it.copy(session = it.session.copy(first = first, second = second)) }; persist()
    }
    fun rename(title: String) { _state.update { it.copy(session = it.session.copy(title = title.trim().ifBlank { "Conversation" })) }; persist() }
    fun saveHistory(enabled: Boolean) {
        prefs.edit().putBoolean("save", enabled).apply()
        // Turning saving off starts a separate temporary conversation: later turns must never leak
        // into an already saved session. Previously saved sessions remain available in History.
        _state.update { it.copy(saveHistory = enabled, session = if (enabled) it.session else ConversationSession(first = it.session.first, second = it.session.second)) }
        if (enabled) persist()
    }
    fun newSession() { if (!_state.value.busy) _state.update { it.copy(session = ConversationSession(first = it.session.first, second = it.session.second), error = null) } }
    fun openSession(session: ConversationSession) { if (!_state.value.busy) _state.update { it.copy(session = session, error = null) } }
    fun deleteSession(id: String) {
        if (_state.value.busy) return
        _state.update { it.copy(history = it.history.filterNot { s -> s.id == id }, session = if (it.session.id == id) ConversationSession(first = it.session.first, second = it.session.second) else it.session) }
        disk.launch { store.delete(id) }
    }
    fun reportError(text: String) { _state.update { it.copy(error = text) } }

    private fun persist() {
        val current = _state.value
        if (!current.saveHistory || current.session.turns.isEmpty()) return
        val snapshot = current.session
        _state.update { it.copy(history = (it.history.filterNot { s -> s.id == snapshot.id } + snapshot).sortedByDescending { s -> s.created }) }
        disk.launch {
            try { store.save(snapshot) }
            catch (e: Exception) { _state.update { it.copy(error = "History: ${e.message ?: e.javaClass.simpleName}") } }
        }
    }

    fun speak(speaker: Int, config: CaptionOverlayConfig) = begin(speaker, config, null)
    fun type(speaker: Int, text: String, config: CaptionOverlayConfig) { if (text.isNotBlank()) begin(speaker, config, text.trim()) }
    fun finish() { finish.complete(Unit); _state.update { it.copy(status = "Finishing", listening = false) } }
    fun cancel() {
        translator?.cancel()
        operation?.cancel()
        runCatching { recorder?.stop() }
    }
    fun retry(turn: ConversationTurn, config: CaptionOverlayConfig) {
        if (!_state.value.busy && turn.original.isNotBlank()) begin(turn.speaker, config, turn.original, turn)
    }

    private fun begin(speaker: Int, config: CaptionOverlayConfig, typed: String?, retry: ConversationTurn? = null) {
        if (_state.value.busy) return
        if (CaptionCaptureService.running.value) { reportError("Stop live captions before starting a conversation."); return }
        val session = _state.value.session
        val source = retry?.source ?: if (speaker == 0) session.first else session.second
        val target = retry?.target ?: if (speaker == 0) session.second else session.first
        if (source == target) { reportError("Choose two different languages."); return }
        val route = "${config.engine.label} / ${config.modelId} → ${config.localTranslationModelId}"
        val turn = retry?.copy(status = TurnStatus.TRANSLATING, error = null) ?: ConversationTurn(speaker = speaker, source = source, target = target, route = route,
            original = typed.orEmpty(), status = if (typed == null) TurnStatus.LISTENING else TurnStatus.TRANSLATING)
        _state.update { it.copy(session = if (retry == null) it.session.copy(turns = it.session.turns + turn) else it.session.updateTurn(session.id, turn),
            busy = true, activeSpeaker = speaker, status = "Preparing", error = null, partial = "") }
        persist()
        val gen = ++generation
        finish = CompletableDeferred()
        operation = scope.launch {
            var lease: AutoCloseable? = null
            var observer: Job? = null
            var currentTurn = turn
            try {
                lease = LocalWorkGate.acquire("Conversation")
                if (typed == null) {
                    val controller = CaptionEngineController(context)
                    engine = controller
                    var recognitionError: String? = null
                    observer = launch { controller.state.collect { s ->
                        if (s.status == CaptionEngineController.Status.ERROR) {
                            recognitionError = s.error ?: "Speech recognition failed"
                            finish.complete(Unit)
                        }
                        val original = s.history.joinToString(" ") { it.original }.trim()
                        if (original != currentTurn.original) {
                            currentTurn = currentTurn.copy(original = original)
                            _state.update { it.copy(session = it.session.updateTurn(session.id, currentTurn)) }; persist()
                        }
                        _state.update { it.copy(partial = s.partial) }
                    } }
                    val ready = CompletableDeferred<Boolean>()
                    controller.start(config.copy(mode = CaptionMode.CAPTIONS, source = CaptionSource.MIC,
                        streamLanguage = source, speakCaptions = false, paused = false, localTranslationEnabled = false)) { ready.complete(it) }
                    check(ready.await()) { controller.state.value.error ?: "Speech recognizer could not start" }
                    coroutineContext.ensureActive()
                    startMicrophone(controller)
                    _state.update { it.copy(status = "Listening", listening = true) }
                    // Explicit bounded turns: avoid unbounded offline windows and encourage a natural handover.
                    withTimeoutOrNull(60_000) { finish.await() }
                    _state.update { it.copy(status = "Finishing", listening = false) }
                    stopMicrophone()
                    recognitionError?.let { error(it) }
                    suspendCancellableCoroutine { continuation -> controller.stop { if (continuation.isActive) continuation.resume(Unit) } }
                    observer.cancelAndJoin(); observer = null
                    currentTurn = currentTurn.copy(original = controller.state.value.history.joinToString(" ") { it.original }.trim())
                    _state.update { it.copy(session = it.session.updateTurn(session.id, currentTurn), partial = "") }; persist()
                    controller.shutdown(); controller.awaitReleased(); engine = null
                }
                check(currentTurn.original.isNotBlank()) { "No speech was recognized. Try speaking closer to the microphone." }
                currentTurn = currentTurn.copy(status = TurnStatus.TRANSLATING)
                _state.update { it.copy(session = it.session.updateTurn(session.id, currentTurn), status = "Translating", partial = "") }; persist()
                val translated = withContext(Dispatchers.IO) {
                    val opened = if (config.localTranslationModelId == TranslationOptions.ML_KIT) PlatformTranslation.open()
                        else TranslationCatalog.find(config.localTranslationModelId).let { spec -> LocalTranslationSession.open(LocalTranslationModels(File(context.filesDir, "translation-models")).file(spec), spec) }
                    translator = opened
                    try { ensureActive(); opened.translate(currentTurn.original, TranslationDirection(source, target)) }
                    finally { opened.close(); translator = null }
                }
                currentTurn = currentTurn.copy(translation = translated, status = TurnStatus.COMPLETE, error = null)
                _state.update { it.copy(session = it.session.updateTurn(session.id, currentTurn)) }; persist()
                onTranslation?.invoke(currentTurn)
            } catch (e: CancellationException) {
                val partial = _state.value.partial
                currentTurn = currentTurn.copy(original = listOf(currentTurn.original, partial).filter { it.isNotBlank() }.joinToString(" "), status = TurnStatus.INTERRUPTED, error = "Interrupted. Review the original text before retrying translation.")
                _state.update { it.copy(session = it.session.updateTurn(session.id, currentTurn)) }; persist()
            } catch (e: Throwable) {
                if (e !is Exception && e !is LinkageError) throw e
                val message = e.message ?: e.javaClass.simpleName
                currentTurn = currentTurn.copy(status = TurnStatus.ERROR, error = message)
                _state.update { it.copy(session = it.session.updateTurn(session.id, currentTurn), error = message) }; persist()
            } finally {
                withContext(NonCancellable) {
                    observer?.cancelAndJoin()
                    stopMicrophone()
                    engine?.let { it.shutdown(); it.awaitReleased() }; engine = null
                    translator?.close(); translator = null
                    lease?.close()
                }
                if (generation == gen) _state.update { it.copy(status = "Ready", busy = false, activeSpeaker = null, listening = false, partial = "") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophone(controller: CaptionEngineController) {
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "AudioRecord.getMinBufferSize returned $min" }
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 6_400)).build()
        recorder = record
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start recording" }
        recording = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(320)
            try {
                while (isActive) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (!isActive) break
                    check(count >= 0) { "Microphone AudioRecord.read returned $count" }
                    if (count > 0) controller.pushAudio(buffer.copyOf(count), count)
                }
            } catch (e: Exception) { if (isActive) withContext(Dispatchers.Main) { reportError(e.message ?: e.javaClass.simpleName); this@ConversationController.cancel() } }
        }
    }
    private suspend fun stopMicrophone() {
        val job = recording; recording = null
        job?.cancel()
        runCatching { recorder?.stop() }
        job?.join()
        recorder?.release(); recorder = null
    }
    override fun close() {
        onTranslation = null
        cancel()
        scope.launch {
            operation?.join()
            scope.cancel()
            disk.launch { store.close(); disk.cancel() }
        }
    }
}
