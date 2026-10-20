package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.runtime.AudioCaptureDevice
import com.sal7one.hearth.desktop.runtime.DesktopAudioCapture
import com.sal7one.hearth.desktop.runtime.DesktopAudioPlayback
import com.sal7one.hearth.integration.DesktopIntegrationServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path

internal data class IntegrationView(val baseUrl: String, val overlayUrl: String)
internal data class DesktopUiState(
    val snapshot: PipelineSnapshot? = null,
    val starting: Boolean = false,
    val finishing: Boolean = false,
    val cancelling: Boolean = false,
    val playingAudio: Boolean = false,
    val microphone: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val devices: List<AudioCaptureDevice> = emptyList(),
    val integration: IntegrationView? = null,
    val integrationBusy: Boolean = false,
    val importingSetup: Boolean = false,
) {
    val active: Boolean get() = starting || finishing || cancelling || snapshot?.terminal == false
}

/** UI owns one session; cancellation joins the actual shared pipeline, never a fake UI timer. */
internal class DesktopWorkspaceController(
    val pipeline: PipelineService,
    val config: StateFlow<PipelineConfig>,
    val dataDirectory: Path,
    scope: CoroutineScope,
    private val persist: suspend (PipelineConfig) -> Unit,
    private val putSecret: suspend (String, String) -> Unit,
    private val removeSecret: suspend (String) -> Unit,
    private val exportSetup: suspend (Path) -> Unit,
    private val importSetup: suspend (Path) -> Unit,
    private val persistUi: suspend (Map<String, String>) -> Unit = {},
    val capabilities: List<EngineDescriptor> = pipeline.capabilities(),
) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + owner)
    private val mutableState = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = mutableState.asStateFlow()
    private var session: PipelineSession? = null
    private var startJob: Job? = null
    private var observation: Job? = null
    private var microphone: DesktopAudioCapture? = null
    private val playback = DesktopAudioPlayback()
    private var playbackJob: Job? = null
    private var server: DesktopIntegrationServer? = null
    private var externalObservation: AutoCloseable? = null
    private val configWrites = Mutex()
    private val integrationWrites = Mutex()
    private val playbackWrites = Mutex()

    fun refreshDevices() = action {
        val devices = withContext(Dispatchers.IO) { DesktopAudioCapture.devices() }
        mutableState.update { it.copy(devices = devices) }
    }

    fun save(config: PipelineConfig) = action {
        configWrites.withLock { persist(config) }
        mutableState.update { it.copy(notice = "Pipeline settings saved") }
    }

    fun updateConfig(change: (PipelineConfig) -> PipelineConfig) = action {
        configWrites.withLock { persist(change(config.value)) }
    }

    fun saveUi(values: Map<String, String>) = action { persistUi(values) }

    fun saveCredential(id: String, secret: String, onSaved: () -> Unit = {}) = action {
        require(id.isNotBlank()) { "Choose a credential reference before saving a key" }
        require(secret.isNotBlank()) { "Enter the provider key" }
        putSecret(id, secret)
        onSaved()
        mutableState.update { it.copy(notice = "Credential saved separately from pipeline settings") }
    }

    fun forgetCredential(id: String) = action {
        require(id.isNotBlank()) { "No credential reference is configured" }
        removeSecret(id)
        mutableState.update { it.copy(notice = "Credential removed") }
    }

    fun start(input: WorkspaceInput, text: String, audioPath: String, deviceId: String?) {
        if (!owner.isActive) return
        if (state.value.importingSetup) { showError("Wait for setup verification to finish"); return }
        if (state.value.active) { showError("Finish or cancel the current session first"); return }
        mutableState.update { it.copy(starting = true, error = null, notice = null, finishing = false, cancelling = false) }
        startJob = scope.launch {
            try {
                val requestInput = when (input) {
                    WorkspaceInput.TEXT -> {
                        require(text.isNotBlank()) { "Enter some text first" }
                        PipelineInput.Text(text)
                    }
                    WorkspaceInput.AUDIO_FILE -> {
                        require(audioPath.isNotBlank()) { "Choose an audio or video file first" }
                        val path = Path.of(audioPath)
                        require(withContext(Dispatchers.IO) { Files.isRegularFile(path) }) { "Audio file does not exist: $audioPath" }
                        PipelineInput.AudioFile(path.toAbsolutePath().toString())
                    }
                    WorkspaceInput.MICROPHONE -> {
                        val capture = DesktopAudioCapture(deviceId)
                        microphone = capture
                        PipelineInput.AudioStream(capture.start())
                    }
                }
                val next = pipeline.start(PipelineRequest(requestInput, config.value))
                observation?.cancel()
                session = next
                mutableState.update { it.copy(starting = false, snapshot = next.state.value, microphone = input == WorkspaceInput.MICROPHONE) }
                observation = scope.launch {
                    next.state.collect { snapshot ->
                        mutableState.update { it.copy(snapshot = snapshot) }
                        if (snapshot.terminal) {
                            microphone?.cancel()
                            microphone = null
                            mutableState.update { it.copy(finishing = false, microphone = false) }
                        }
                    }
                }
                // The real session stays visible/cancellable even if an optional OBS observer fails.
                try {
                    integrationWrites.withLock {
                        externalObservation?.close(); externalObservation = null
                        externalObservation = server?.observe(next)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { showError("Caption integration: ${e.message ?: e}") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                microphone?.cancel(); microphone = null
                mutableState.update { it.copy(starting = false, microphone = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun finishMicrophone() = action {
        val capture = microphone ?: error("No microphone recording is active")
        mutableState.update { it.copy(finishing = true) }
        try { capture.finish() }
        catch (e: Exception) {
            mutableState.update { it.copy(finishing = false) }
            throw e
        }
    }

    fun cancel() = action {
        mutableState.update { it.copy(cancelling = true) }
        try {
            startJob?.cancelAndJoin()
            microphone?.cancel()
            playback.cancel()
            session?.cancel()
        } finally {
            microphone = null
            mutableState.update { it.copy(starting = false, cancelling = false, finishing = false, microphone = false) }
        }
    }

    fun play(audio: GeneratedAudio) {
        playbackJob?.cancel()
        playback.cancel()
        playbackJob = action {
            // Hold ownership until the previous caller's joined native/audio cleanup has completed.
            playbackWrites.withLock {
                mutableState.update { it.copy(playingAudio = true) }
                try { playback.play(audio.path) }
                finally { mutableState.update { it.copy(playingAudio = false) } }
            }
        }
    }

    fun stopPlayback() { playback.cancel(); playbackJob?.cancel() }

    fun startIntegration() = action {
        integrationWrites.withLock {
            if (server != null) return@withLock
            mutableState.update { it.copy(integrationBusy = true) }
            try {
                // Assign inside IO: cancellation at the dispatcher handoff must not lose a bound server.
                withContext(Dispatchers.IO) {
                    server = DesktopIntegrationServer(pipeline, { config.value }, dataDirectory).start()
                }
                val next = checkNotNull(server)
                try {
                    externalObservation = session?.let(next::observe)
                    mutableState.update { it.copy(integration = IntegrationView(next.baseUrl, next.overlayUrl)) }
                } catch (e: Exception) {
                    try { withContext(Dispatchers.IO + NonCancellable) { next.close() } }
                    catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                    finally { server = null; externalObservation = null }
                    throw e
                }
            } finally { mutableState.update { it.copy(integrationBusy = false) } }
        }
    }

    fun stopIntegration() = action {
        integrationWrites.withLock {
            mutableState.update { it.copy(integrationBusy = true) }
            try {
                externalObservation?.close(); externalObservation = null
                val previous = server
                // Finish closing even if application shutdown cancels this UI action.
                withContext(Dispatchers.IO + NonCancellable) { previous?.close() }
                server = null
                mutableState.update { it.copy(integration = null) }
            } finally { mutableState.update { it.copy(integrationBusy = false) } }
        }
    }

    fun copyApiToken(copy: (String) -> Unit) = action {
        val running = server ?: error("Start the local integration first")
        copy(running.apiToken)
        mutableState.update { it.copy(notice = "API token copied. Keep it private; it can start and cancel requests.") }
    }

    fun exportSetup(path: Path) = action { exportSetup.invoke(path); mutableState.update { it.copy(notice = "Setup exported without provider keys") } }
    fun importSetup(path: Path) = action {
        check(!state.value.importingSetup) { "A setup import is already in progress" }
        mutableState.update { it.copy(importingSetup = true) }
        try {
            configWrites.withLock { importSetup.invoke(path) }
            mutableState.update { it.copy(notice = "Setup imported. Missing models and provider credentials still need configuration.") }
        } finally { mutableState.update { it.copy(importingSetup = false) } }
    }
    fun exportTranscript(path: Path, translated: Boolean) = action {
        val text = finalTranscript(state.value.snapshot, translated)
        require(text.isNotBlank()) { "There is no completed ${if (translated) "translation" else "transcript"} to export" }
        withContext(Dispatchers.IO) { Files.writeString(path, text) }
        mutableState.update { it.copy(notice = "Saved ${path.fileName}") }
    }

    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun clearNotice() { mutableState.update { it.copy(error = null, notice = null) } }

    suspend fun close() {
        owner.cancelAndJoin()
        // Every resource gets its cleanup attempt, including after a transport/native close fails.
        withContext(NonCancellable) {
            var failure: Exception? = null
            suspend fun release(block: suspend () -> Unit) {
                try { block() }
                catch (e: Exception) {
                    if (failure == null) failure = e else failure!!.addSuppressed(e)
                }
            }
            release { microphone?.cancel(); microphone = null }
            release { playback.cancel() }
            release { session?.cancel() }
            release { externalObservation?.close(); externalObservation = null }
            release { withContext(Dispatchers.IO) { server?.close() }; server = null }
            release { playback.close() }
            failure?.let { throw it }
        }
    }

    private fun action(block: suspend () -> Unit): Job = scope.launch {
        try { mutableState.update { it.copy(error = null, notice = null) }; block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.update { it.copy(error = e.message ?: e.toString()) } }
    }
}
