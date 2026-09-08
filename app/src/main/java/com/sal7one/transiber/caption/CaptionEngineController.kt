package com.sal7one.transiber.caption

import android.content.Context
import android.util.Log
import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.model.AudioChunk
import com.sal7one.transiber.byok.ApiKeyStore
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.byok.RemoteWhisperEngine
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.model.LanguageConfig
import com.sal7one.common_jni.model.SttConfig
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.model.VoskAssetModelProvider
import com.sal7one.transiber.ui.state.ScreenState
import com.sal7one.transiber.ui.state.stateInScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * One finalized caption utterance: what was heard, and (when a translation
 * target is active and served) its translation.
 */
data class CaptionLine(
    val original: String,
    val translation: String? = null,
    /** Stable identity so a queued translation attaches to the exact line. */
    val id: Long = 0L,
)

/** Grace after a Whisper voice-end (partial cleared) before promoting. */
const val VOICE_END_GRACE_MS = 700L

/** Fixed fallback for engines that never clear their partial at voice end. */
const val SILENCE_PROMOTE_MS = 5_000L

/** How long a partial must stay unchanged before opportunistic translation. */
const val STABLE_PARTIAL_MS = 1_200L

/**
 * Character budget for the live partial. Engines that never endpoint under
 * continuous audio (Vosk over music/talk is the classic case) keep extending
 * their partial without bound — the overlay then gained a line of height per
 * new text instead of scrolling. Past this budget the head is promoted into
 * history and only the recent tail stays live.
 */
const val PARTIAL_MAX_CHARS = 200

/** What the poller should do for one observed partial. */
sealed interface PromotionDecision {
    data object Hold : PromotionDecision

    /** Promote [text] — the last partial — because the utterance ended. */
    data class Promote(val text: String) : PromotionDecision

    /** Promote [promote] (non-prefix change), then track [track] as the new partial. */
    data class PromoteAndTrack(val promote: String, val track: String) : PromotionDecision

    /** Track [text] as the live partial. */
    data class Track(val text: String) : PromotionDecision

    /**
     * In-window rewrite (non-extension): promote nothing, publish nothing —
     * the stable-display lock holds the shown line and [text] only updates
     * the internal [lastPartial] so the endpoint promotes the settled text.
     */
    data class TrackStable(val text: String) : PromotionDecision
}

/**
 * Stable-display gate: within one utterance window the visible live line may
 * only be replaced by a STRICT extension of what is already shown. Anything
 * shorter, equal-length-but-different, or a non-prefix rewrite is held —
 * this is what stops whisper's A/B hypothesis oscillation from flickering
 * the overlay.
 */
fun stableLiveText(candidate: String, displayed: String): String =
    if (displayed.isNotEmpty() &&
        !(candidate.length > displayed.length && candidate.startsWith(displayed))
    ) displayed else candidate

/**
 * Pure, side-effect-free promotion decision (unit-testable).
 *
 * When [trustVoiceEnd] is true (Whisper clears its published partial at the
 * utterance boundary), an empty partial after text promotes after
 * [VOICE_END_GRACE_MS]. Otherwise the fixed [SILENCE_PROMOTE_MS] clock from
 * the last non-empty partial is the fallback. An empty [lastPartial] never
 * promotes, so a fresh session/restart cannot flush stale text.
 */
fun decidePromotion(
    partial: String,
    lastPartial: String,
    lastPartialAtMs: Long,
    emptySinceMs: Long?,
    now: Long,
    trustVoiceEnd: Boolean,
): PromotionDecision {
    if (partial.isEmpty()) {
        if (lastPartial.isEmpty()) return PromotionDecision.Hold
        val promoteNow = if (trustVoiceEnd) {
            now - (emptySinceMs ?: now) >= VOICE_END_GRACE_MS
        } else {
            now - lastPartialAtMs >= SILENCE_PROMOTE_MS
        }
        return if (promoteNow) PromotionDecision.Promote(lastPartial) else PromotionDecision.Hold
    }
    if (lastPartial.isNotEmpty() && !partial.startsWith(lastPartial)) {
        // In-window rewrite: do NOT promote (that flooded history with
        // A,B,A,B alternates and flickered the live line). Track the latest
        // hypothesis privately; the stable-display lock holds the shown text.
        return PromotionDecision.TrackStable(partial)
    }
    if (partial.length > PARTIAL_MAX_CHARS) {
        // Length safety net: roll the head of an over-long partial into
        // history so the live text (and the bubble) stays bounded even when
        // the engine never endpoints.
        val cut = partialSplitIndex(partial)
        return PromotionDecision.PromoteAndTrack(
            promote = partial.substring(0, cut).trim(),
            track = partial.substring(cut).trim(),
        )
    }
    return PromotionDecision.Track(partial)
}

/**
 * Index to split an over-long partial at: the last space when there is one,
 * else a few characters from the end (CJK has no spaces), never inside a
 * surrogate pair.
 */
private fun partialSplitIndex(partial: String): Int {
    val space = partial.lastIndexOf(' ')
    if (space > 0) return space
    var cut = partial.length - 2
    if (cut > 0 && Character.isHighSurrogate(partial[cut])) cut--
    return cut.coerceAtLeast(1)
}

/**
 * Strips the already-promoted prefix from a live partial so the UI never
 * repeats finalized text (the whisper tail-window re-decodes the promoted
 * region). Word-boundary aware: only complete words are cut; a partial that
 * merely starts with a suffix of [promoted] mid-word is returned untouched.
 */
fun stripPromotedPrefix(partial: String, promoted: String?): String {
    if (promoted.isNullOrBlank() || partial.isBlank()) return partial
    val rest = partial.removePrefix(promoted)
    if (rest != partial) {
        if (rest.isEmpty()) return ""
        return if (rest.first().isWhitespace() || promoted.last().isWhitespace()) {
            rest.trimStart()
        } else {
            partial
        }
    }
    // Tail-echo case: the partial re-emits the end of the promoted line.
    for (cut in promoted.indices) {
        val suffix = promoted.substring(cut)
        if (partial.startsWith(suffix)) {
            val tail = partial.removePrefix(suffix)
            if (tail.isEmpty()) return ""
            if (tail.first().isWhitespace() || suffix.last().isWhitespace()) return tail.trimStart()
            return partial
        }
    }
    return partial
}

/** Attaches [translated] to the exact line identified by [lineId]. */
fun attachTranslationById(
    history: List<CaptionLine>,
    lineId: Long,
    translated: String,
): List<CaptionLine> = history.map { line ->
    if (line.id == lineId && line.translation == null) line.copy(translation = translated) else line
}

/**
 * Owns the streaming STT engine behind the live caption overlay.
 *
 * Audio arrives as 16-bit mono PCM from the capture service and is streamed
 * through the engine's zero-copy direct path. Ordering is guaranteed by a
 * single-consumer dispatcher: `scope.launch` on a parallel dispatcher would
 * race consecutive chunks and corrupt the transcript stream.
 *
 * Translation architecture:
 * - ENGLISH target: Whisper's built-in translate task does the work during
 *   transcription (one pass, no second model).
 * - Other targets (Arabic): the engine captions in the original language and
 *   finalized utterances are translated by [TranslationLayer] on a background
 *   dispatcher. Until an MT model + runtime are active, translation fails
 *   closed with an actionable notice while original captions keep flowing.
 */
class CaptionEngineController(
    private val context: Context,
    private val externalScope: CoroutineScope? = null,
) {
    enum class Status { IDLE, LOADING_MODEL, RUNNING, CAPTURE_SILENT, STOPPED, ERROR }

    /** A selectable speech model of the running engine type. */
    data class ModelOption(val id: String, val name: String)

    data class State(
        val status: Status = Status.IDLE,
        val history: List<CaptionLine> = emptyList(),
        val partial: String = "",
        val partialTranslation: String? = null,
        val translationNotice: String? = null,
        val error: String? = null,
        val modelName: String? = null,
        val engineLabel: String = "",
        val localSpeechMetrics: String? = null,
        val localTranslationMetrics: String? = null,
        // All valid imported models of the running engine type (for the
        // picker: Arabic vs English Vosk, tiny vs base Whisper, ...).
        val availableModels: List<ModelOption> = emptyList(),
    )

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Single-consumer dispatcher: chunk order in, chunk order out. */
    private val audioDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)
    private val translationDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    private var localTranslationBridge: com.sal7one.common_jni.translation.CaptionTranslationBridge? = null
    private var translationCleanup: Job? = null
    private var translationGeneration = 0L

    private var translationScope = CoroutineScope(SupervisorJob() + translationDispatcher)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Derived live-session shell state ([ScreenState] via [stateInScreen]),
     * folded purely by [captionScreenState]. Additive view of [state] — the
     * engine loop, poller and window logic are untouched.
     */
    val screenState: StateFlow<ScreenState<CaptionSessionContent>> =
        _state.map { captionScreenState(it) }
            .stateInScreen(scope)

    /** Live config source so the poller respects runtime setting changes. */
    @Volatile
    var currentConfig: CaptionOverlayConfig = CaptionOverlayConfig()
        private set

    /**
     * Second-stage on-device translation backend. The model directory
     * provider reads the registry live, so importing (or deleting) a
     * translation model takes effect on the next availability check.
     */
    private val onnxTranslator = OnnxMarianTranslator(
        modelDirectoryProvider = {
            ModelRegistry.getInstance(context)
                .getModelsForEngine(ModelEngineType.TRANSLATE)
                .firstOrNull()
                ?.let { java.io.File(it.path) }
        },
    )

    private val translationLayer = TranslationLayer(
        backends = listOf(onnxTranslator),
    )

    @Volatile private var engine: SttEngine? = null
    private var engineCleanup: Job? = null
    @Volatile private var localSpeech: com.sal7one.common_jni.speech.LiveSpeechProcessor? = null

    private data class LoadedCaptionEngine(
        val legacy: SttEngine? = null,
        val speech: com.sal7one.common_jni.speech.LiveSpeechProcessor? = null,
    ) {
        suspend fun release() { legacy?.release(); speech?.close() }
    }


    /** Timestamp of the last visible caption activity (final promoted or
     * interim shown) — the source-stop drain waits for this to go quiet. */
    @Volatile private var lastActivityMs = 0L
    @Volatile private var stopping = false
    @Volatile private var sessionGeneration = 0L
    private val loadMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when the STT engine itself emits TARGET-language text (the
     * OpenAI realtime translation session) — the second-stage Marian
     * translator must stay out of the way, or it would translate the
     * already-translated output a second time. */
    @Volatile private var engineTranslatesToTarget = false

    @Volatile private var activeRoute = CaptionTranslationRoute.ORIGINAL

    private fun needsSecondStage(config: CaptionOverlayConfig): Boolean =
        config.mode == CaptionMode.TRANSLATE && activeRoute.secondStage

    // Zero-copy direct-buffer push is declared on the concrete engine classes,
    // not the SttEngine interface; captured here at load time.
    private var pushDirect: (suspend (ByteBuffer, Int) -> Unit)? = null

    private var pollJob: Job? = null
    private var audioJob: Job? = null
    @Volatile private var audioChannel = Channel<ShortArray>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var lastPartial: String = ""
    /** Text currently shown as the live line (stable-display anchor). */
    private var displayedPartial: String = ""
    private var lastPartialAtMs: Long = 0L

    /** When the partial first went empty after having text (voice-end clock). */
    private var emptySinceMs: Long? = null

    /** When the current partial first appeared (stability clock). */
    private var partialSinceMs: Long? = null

    /** Stable identity for promoted lines, safe across threads. */
    private val nextLineId = AtomicLong(0L)

    /** Last partial we opportunistically translated, plus its result. */
    @Volatile
    private var opportunisticText: String? = null

    @Volatile
    private var opportunisticTranslation: String? = null

    fun start(config: CaptionOverlayConfig, onComplete: (Boolean) -> Unit) {
        scope.launch {
            currentConfig = config
            stopping = false
            val generation = ++sessionGeneration
            lastActivityMs = System.currentTimeMillis()
            stopInternal(promotePending = false)
            translationScope = CoroutineScope(SupervisorJob() + translationDispatcher)
            audioChannel.close()
            audioChannel = Channel(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            _state.value = State(status = Status.LOADING_MODEL)
            if (config.paused) return@launch
            loadMutex.withLock {
                // Don't load another large model while the previous native inference is releasing.
                engineCleanup?.join()
                if (generation != sessionGeneration) return@withLock
                try {
                    // Initialization owns native handles until it can hand them back;
                    // cancellation must not discard a newly allocated engine.
                    val loaded = withContext(NonCancellable) { loadEngine(config) }
                    if (generation != sessionGeneration || !isActive) {
                        engineCleanup = cleanupScope.launch { loaded.release() }
                        return@withLock
                    }
                    engine = loaded.legacy
                    localSpeech = loaded.speech
                    val notice = withContext(Dispatchers.IO) { translationNoticeFor(config) }
                    if (generation != sessionGeneration) return@withLock
                    _state.update { it.copy(status = Status.RUNNING, translationNotice = notice) }
                    configureLocalTranslation(config)
                    startAudioConsumer()
                    startPolling()
                    onComplete(true)
                } catch (e: CancellationException) { throw e }
                catch (e: LinkageError) {
                    if (generation == sessionGeneration) {
                        reportError(e.toString())
                        onComplete(false)
                    }
                }
                catch (e: Exception) {
                    if (generation == sessionGeneration) {
                        reportError(e.message ?: e.javaClass.simpleName)
                        onComplete(false)
                    }
                }
            }
        }
    }

    fun reportError(message: String) {
        _state.update { it.copy(status = Status.ERROR, error = message) }
    }

    /** Stop processing immediately; the service retains consent for Resume. */
    fun pauseProcessing() {
        ++sessionGeneration
        stopping = true
        audioChannel.close()
        stopInternal(promotePending = false)
    }

    /** Config mutations that do not need an engine restart apply live. */
    fun updateRuntimeConfig(config: CaptionOverlayConfig) {
        // A failed capture has released projection/recorder. Only a fresh
        // service start can reacquire them; styling must not restart STT alone.
        if (_state.value.status == Status.ERROR) return
        val previous = currentConfig
        currentConfig = config
        val localOnlyChange = previous.effectiveEngine.speechBackend != null &&
            previous.effectiveEngine == config.effectiveEngine && previous.modelId == config.modelId &&
            previous.streamLanguage == config.streamLanguage && previous.paused == config.paused
        if (localOnlyChange && !config.paused && localSpeech != null) {
            if (previous.mode != config.mode || previous.target != config.target ||
                previous.localTranslationEnabled != config.localTranslationEnabled || previous.localTranslationModelId != config.localTranslationModelId) {
                activeRoute = captionTranslationRoute(config, com.sal7one.transiber.byok.CloudConfigStore.SttMode.BATCH)
                configureLocalTranslation(config)
            }
            return
        }
        val engineRelevant =
            previous.mode != config.mode ||
                previous.target != config.target ||
                previous.effectiveEngine != config.effectiveEngine ||
                previous.modelId != config.modelId ||
                previous.localTranslationEnabled != config.localTranslationEnabled ||
                previous.localTranslationModelId != config.localTranslationModelId ||
                // A newly pinned stream language only takes effect at engine
                // initialize — without this the overlay's language chips are
                // inert mid-session.
                previous.streamLanguage != config.streamLanguage
        if (config.paused) {
            pauseProcessing()
        } else if (previous.paused || engineRelevant) {
            start(config) { }
        }
    }

    private fun configureLocalTranslation(config: CaptionOverlayConfig) {
        val previous = localTranslationBridge
        localTranslationBridge = null
        previous?.close()
        val cleanup = translationCleanup
        translationCleanup = cleanupScope.launch { cleanup?.join(); previous?.awaitClosed() }
        val generation = ++translationGeneration
        _state.update { it.copy(translationNotice = null, localTranslationMetrics = null) }
        if (activeRoute != CaptionTranslationRoute.LOCAL_TEXT) return
        if (config.localTranslationModelId.isBlank()) {
            _state.update { it.copy(translationNotice = "Import and select a local translation model in Models. Original CC continues.") }
            return
        }
        val pendingCleanup = translationCleanup
        localTranslationBridge = com.sal7one.common_jni.translation.CaptionTranslationBridge(
            scope = scope, target = config.target.languageTag,
            open = {
                pendingCleanup?.join()
                val spec = com.sal7one.common_jni.translation.TranslationCatalog.find(config.localTranslationModelId)
                val models = com.sal7one.transiber.translation.LocalTranslationModels(java.io.File(context.filesDir, "translation-models"))
                scope.launch { if (generation == translationGeneration) _state.update {
                    it.copy(localTranslationMetrics = "Loading ${spec.label} · CC continues")
                } }
                CaptionDiagnostics.record(context, "${spec.label}: loading local translation model")
                com.sal7one.common_jni.translation.LocalTranslationSession.open(models.file(spec), spec)
            },
            result = { id, text, latency ->
                scope.launch {
                    if (generation == translationGeneration && activeRoute == CaptionTranslationRoute.LOCAL_TEXT) {
                        _state.update { it.copy(history = attachTranslationById(it.history, id, text), localTranslationMetrics = "Local translation ${latency} ms · ${config.localTranslationModelId}") }
                        speakLine(currentConfig, text)
                    }
                }
            },
            notice = { message -> scope.launch {
                if (generation == translationGeneration) _state.update { it.copy(translationNotice = message) }
            } },
        )
    }

    private fun enqueueLocalTranslation(lineId: Long, text: String, sourceLanguage: String?) {
        if (activeRoute != CaptionTranslationRoute.LOCAL_TEXT) return
        // Explicit user source overrides uncertain/mixed recognition metadata; never use target as source.
        val source = currentConfig.streamLanguage.takeUnless { it == "auto" } ?: sourceLanguage
        localTranslationBridge?.offer(lineId, text, source)
    }

    private fun translationNoticeFor(config: CaptionOverlayConfig): String? = when (activeRoute) {
        CaptionTranslationRoute.UNSUPPORTED -> "Original CC is running. Enable the local translation bridge and select a translation model to translate this speech."
        CaptionTranslationRoute.ENGLISH_PIVOT, CaptionTranslationRoute.ENGLISH_TEXT ->
            if (translationLayer.isAvailable(config.target)) null else translationLayer.unavailabilityReason(config.target) +
                " For live cloud translation without a local model, choose Live Arabic on the setup screen."
        else -> null
    }

    private suspend fun loadEngine(config: CaptionOverlayConfig): LoadedCaptionEngine = withContext(Dispatchers.IO) {
        engineTranslatesToTarget = false
        activeRoute = captionTranslationRoute(config,
            if (config.engine == CaptionEngineChoice.CLOUD) com.sal7one.transiber.byok.CloudConfigStore.sttMode(context)
            else com.sal7one.transiber.byok.CloudConfigStore.SttMode.BATCH)
        if (config.effectiveEngine.speechBackend != null) {
            val models = LocalSpeechModels(java.io.File(context.filesDir, "speech-models"))
            val model = models.select(config.effectiveEngine, config.modelId)
            val modelOptions = models.list().filter { it.profile.backend == model.profile.backend }
                .map { ModelOption(it.id, it.profile.label) }
            val runtime = com.sal7one.common_jni.speech.SpeechRuntime()
            CaptionDiagnostics.record(context, "${model.profile.label}: probing runtime")
            val availability = runtime.availability(model.profile.backend)
            check(availability.available) { availability.error ?: "${model.profile.label} runtime unavailable" }
            val session = runtime.open(
                model.root,
                com.sal7one.common_jni.speech.SpeechOptions(
                    sourceLanguage = if (config.effectiveEngine == CaptionEngineChoice.QWEN) "auto" else config.streamLanguage,
                ),
                onStage = { stage -> CaptionDiagnostics.record(context, "${model.profile.label}: $stage") },
            )
            val processor = try {
                check(session.profile == model.profile) { "Speech package profile changed during load" }
                com.sal7one.common_jni.speech.LiveSpeechProcessor(session, maxQueuedSamples = 48000)
            } catch (e: Throwable) { session.close(); throw e }
            pushDirect = null
            _state.update { it.copy(
                modelName = model.profile.label,
                engineLabel = model.profile.label,
                availableModels = modelOptions,
            ) }
            return@withContext LoadedCaptionEngine(speech = processor)
        }
        // ── NETWORK PATH (BYOK) ──────────────────────────────────────────
        // Cloud whisper with the user's own key — play distribution only.
        // In the FOSS build ByokPolicy.FEATURE_BYOK is false and we fall
        // through to the on-device engines (see byok/ByokPolicy.kt for the
        // offline-first contract).
        if (config.effectiveEngine == CaptionEngineChoice.CLOUD) {
            if (!ByokPolicy.cloudEngineAvailable()) {
                throw IllegalStateException(
                    "Cloud engine needs the play distribution and an API key — " +
                        "paste it on the Live captions screen under " +
                        "'Cloud engine · your API key'. Or pick Whisper/Vosk.",
                )
            }
            val cloudStore = com.sal7one.transiber.byok.CloudConfigStore
            val sttMode = cloudStore.sttMode(context)
            if (sttMode != com.sal7one.transiber.byok.CloudConfigStore.SttMode.BATCH) {
                // TRUE STREAMING: WebSocket interim results per provider.
                val client: com.sal7one.transiber.byok.StreamingSttClient = when (sttMode) {
                    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_DEEPGRAM ->
                        com.sal7one.transiber.byok.DeepgramStreamingClient(
                            apiKey = ApiKeyStore.getDeepgramKey(context).ifBlank {
                                throw IllegalStateException(
                                    "Deepgram API key missing — add it in the cloud settings",
                                )
                            },
                        )
                    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_OPENAI ->
                        if (config.mode == CaptionMode.TRANSLATE) {
                            // Native live translation: ANY source language →
                            // target, from OpenAI's realtime translation
                            // session (live-verified 2026-08-24 — English
                            // audio streamed as Arabic deltas). The STT
                            // output IS the target language, so the Marian
                            // second stage stays off.
                            engineTranslatesToTarget = true
                            com.sal7one.transiber.byok.OpenAiTranslateClient(
                                apiKey = ApiKeyStore.getOpenAiKey(context).ifBlank {
                                    throw IllegalStateException(
                                        "OpenAI API key missing — add it in the cloud settings",
                                    )
                                },
                                targetLanguage = config.target.languageTag,
                            )
                        } else {
                            com.sal7one.transiber.byok.OpenAiRealtimeClient(
                                sourceLanguage = config.streamLanguage,
                                apiKey = ApiKeyStore.getOpenAiKey(context).ifBlank {
                                    throw IllegalStateException(
                                        "OpenAI API key missing — add it in the cloud settings",
                                    )
                                },
                            )
                        }
                    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI ->
                        com.sal7one.transiber.byok.AssemblyAiStreamingClient(
                            apiKey = ApiKeyStore.getAssemblyAiKey(context).ifBlank {
                                throw IllegalStateException(
                                    "AssemblyAI API key missing — add it in the cloud settings",
                                )
                            },
                        )
                    else -> throw IllegalStateException("Unsupported streaming mode $sttMode")
                }
                val providerLabel = if (engineTranslatesToTarget) {
                    "Streaming · OpenAI Translate"
                } else {
                    sttMode.label
                }
                val streaming = com.sal7one.transiber.byok.StreamingCloudEngine(
                    client = client,
                    providerLabel = providerLabel,
                )
                streaming.initialize("", SttConfig.forStreaming()).getOrThrow()
                pushDirect = null
                _state.value = _state.value.copy(
                    modelName = "stream:" + providerLabel,
                    engineLabel = providerLabel,
                    availableModels = emptyList(),
                )
                return@withContext LoadedCaptionEngine(legacy = streaming)
            }

            check(ApiKeyStore.hasOpenAiKey(context)) { "Cloud STT: API key missing" }
            val remote = RemoteWhisperEngine(
                apiKey = ApiKeyStore.getOpenAiKey(context),
                baseUrl = cloudStore.baseUrl(context),
                model = cloudStore.sttModel(context),
            )
            remote.initialize(
                "",
                SttConfig.forStreaming().copy(
                    language = if (config.streamLanguage.isNotBlank() &&
                        config.streamLanguage != "auto"
                    ) {
                        LanguageConfig.Specific(config.streamLanguage)
                    } else {
                        LanguageConfig.Auto
                    },
                    translateToEnglish = activeRoute.sttToEnglish,
                ),
            ).getOrThrow()
            pushDirect = null
            _state.value = _state.value.copy(
                modelName = "cloud:" + cloudStore.sttModel(context),
                engineLabel = "Cloud Whisper",
                availableModels = emptyList(),
            )
            return@withContext LoadedCaptionEngine(legacy = remote)
        }

        val registry = ModelRegistry.getInstance(context)
        val engineType = when (config.effectiveEngine) {
            CaptionEngineChoice.VOSK -> ModelEngineType.VOSK
            else -> ModelEngineType.WHISPER
        }
        val candidates = registry.getModelsForEngine(engineType)
        val model = candidates.firstOrNull { it.id == config.modelId } ?: candidates.firstOrNull()
            ?: throw IllegalStateException(
                "No ${engineType.displayName} model imported. Import one from the model catalogue first.",
            )

        val provider = registry.getProvider(model.id)
            ?: throw IllegalStateException("Model provider missing for ${model.id}")
        if (provider is VoskAssetModelProvider && !provider.prepareModel()) {
            throw IllegalStateException("Failed to prepare Vosk model ${model.id}")
        }
        val path = provider.getModelPath()
        val digest = provider.getModelDigest()
            ?: throw IllegalStateException("Model provider published no digest for ${model.id}")

        val isWhisper = engineType == ModelEngineType.WHISPER
        val sttConfig = SttConfig.forStreaming().copy(
            // Matrix-driven default (docs/benchmarks.md → "Matrix-driven defaults",
            // benchmarks/matrix-whisper.md): the leading live-caption model is
            // ggml-base-q5_1 (under-budget fallback ggml-tiny-q8_0), run at 8 threads.
            // Whisper's encoder scales across all 8 cores of the S22; the streaming
            // worker decouples inference from the audio path, so the extra threads make
            // partials faster without blocking captures (tuning log: push_max −81 % for
            // only ~17 % batch throughput).
            numThreads = if (isWhisper) 8 else 2,
            streamingChunkDurationMs = if (isWhisper) 200 else 50,
            // Pinned stream language skips detection (faster + more accurate;
            // auto detection is unreliable on short windows, whisper.cpp #445).
            language = if (config.streamLanguage.isNotBlank() &&
                config.streamLanguage != "auto"
            ) {
                LanguageConfig.Specific(config.streamLanguage)
            } else {
                LanguageConfig.Auto
            },
            modelSha256 = digest.hex,
            // Arabic's local Marian model accepts English: Whisper supplies
            // the English pivot even when the source is Russian or Chinese.
            translateToEnglish = activeRoute.sttToEnglish,
        )

        val created: SttEngine = if (isWhisper) WhisperEngine() else VoskEngine()
        val initialization = created.initialize(path, sttConfig)
        if (initialization.isFailure) {
            created.release()
            initialization.getOrThrow()
        }
        pushDirect = if (isWhisper) {
            val whisper = created as WhisperEngine
            { buf, bytes -> whisper.pushAudioDirect(buf, 0, bytes, SAMPLE_RATE).getOrThrow() }
        } else {
            val vosk = created as VoskEngine
            { buf, bytes -> vosk.pushAudioDirect(buf, 0, bytes, SAMPLE_RATE).getOrThrow() }
        }
        _state.value = _state.value.copy(
            modelName = model.name,
            engineLabel = created.engineType.displayName,
            availableModels = candidates.map { ModelOption(it.id, it.name) },
        )
        LoadedCaptionEngine(legacy = created)
    }

    /**
     * Single ordered consumer: pulls staged buffers from the channel in the
     * exact order the capture thread produced them.
     */
    private fun startAudioConsumer() {
        audioJob?.cancel()
        val channel = audioChannel
        val activeEngine = engine ?: return
        val direct = pushDirect
        val generation = sessionGeneration
        audioJob = scope.launch(audioDispatcher) {
            try {
                var nativePcm = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
                for (samples in channel) {
                    if (generation != sessionGeneration || currentConfig.paused) break
                    if (direct != null) {
                        val bytes = samples.size * 2
                        if (nativePcm.capacity() < bytes) nativePcm = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                        nativePcm.clear()
                        nativePcm.asShortBuffer().put(samples)
                        nativePcm.limit(bytes)
                        direct(nativePcm, bytes)
                    } else {
                        activeEngine.pushAudioChunk(AudioChunk(samples, SAMPLE_RATE, 0L,
                            samples.size * 1000L / SAMPLE_RATE)).getOrThrow()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == sessionGeneration) reportError("Audio engine: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        lastPartial = ""
        displayedPartial = ""
        lastPartialAtMs = System.currentTimeMillis()
        emptySinceMs = null
        partialSinceMs = null
        resetOpportunistic()
        pollJob = scope.launch {
            while (isActive) {
                val config = currentConfig
                delay(
                    when (config.effectiveEngine) {
                        CaptionEngineChoice.VOSK -> 500L
                        CaptionEngineChoice.CLOUD, CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON -> 100L
                        CaptionEngineChoice.WHISPER -> 700L
                    },
                )
                val local = localSpeech?.snapshot()
                if (local != null) {
                    val failure = local.state as? com.sal7one.common_jni.speech.SpeechProcessorState.Failed
                    if (failure != null) {
                        reportError(failure.error.message ?: failure.error.toString())
                        break
                    }
                    local.finals.forEach { promoteFinal(it.text, it.sourceLanguage) }
                    val text = local.partial?.text.orEmpty()
                    if (text.isNotBlank() || local.finals.isNotEmpty()) lastActivityMs = System.currentTimeMillis()
                    val metrics = String.format(java.util.Locale.ROOT, "Audio queue %.1fs · compute/audio %s",
                        local.pendingSamples / 16000.0,
                        local.realTimeFactor?.let { String.format(java.util.Locale.ROOT, "%.2f×", it) } ?: "measuring…")
                    _state.update { it.copy(partial = if (config.showPartial) text else "", partialTranslation = null,
                        localSpeechMetrics = metrics) }
                    continue
                }
                val current = engine ?: break
                val streaming = current as? com.sal7one.transiber.byok.StreamingCloudEngine
                val snapshot = streaming?.takeSnapshot()
                val partial = try { snapshot?.partial ?: current.getPartialTranscript()?.text?.trim().orEmpty() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { reportError("Transcription: ${e.message}"); break }
                val now = System.currentTimeMillis()
                if (partial.isNotEmpty()) lastActivityMs = now

                // Cloud/BYOK transient failures surface in the overlay so the
                // user knows why lines stopped (quota, network, bad key).
                (current as? RemoteWhisperEngine)?.takeLastErrorForUi()?.let { cloudError ->
                    Log.w(TAG, "Cloud STT: $cloudError")
                    _state.update { it.copy(translationNotice = cloudError) }
                }
                (current as? RemoteWhisperEngine)?.takeLastNoticeForUi()?.let { cloudNotice ->
                    Log.i(TAG, "Cloud STT notice: $cloudNotice")
                    _state.update { it.copy(translationNotice = cloudNotice) }
                }
                // Streaming engines deliver SETTLED utterances here — promote
                // each final straight into history (real-time utterance
                // boundaries, no batch round-trip).
                snapshot?.error?.let { cloudError ->
                    Log.w(TAG, "Streaming STT: $cloudError")
                    reportError(cloudError)
                }
                if (_state.value.status == Status.ERROR) break
                snapshot?.finals?.forEach { final -> promoteFinal(final) }
                (current as? RemoteWhisperEngine)?.takeFinals()?.forEach { final -> promoteFinal(final) }

                if (streaming != null || current is RemoteWhisperEngine) {
                    // Streaming interims are DISPLAY-ONLY: history comes
                    // exclusively from takeFinals above. Routing them through
                    // the whisper promotion machinery produced BOTH reported
                    // artifacts — the stable-display lock held OLD text when
                    // a fresh utterance's interim was shorter than the last
                    // line, and promote+strip cycles tore single words off
                    // into history (word-by-word display). Providers emit
                    // the accumulated utterance (or replace it in place), so
                    // the raw interim is already safe to render as-is.
                    lastPartial = partial
                    lastPartialAtMs = now
                    displayedPartial = partial
                    _state.update { current ->
                        current.copy(
                            partial = if (config.showPartial && !config.paused) partial else "",
                            partialTranslation = null,
                        )
                    }
                    continue
                }

                if (partial.isNotEmpty() && partial != lastPartial) {
                    Log.i(
                        TAG,
                        "Partial (${partial.length} chars, +${now - lastPartialAtMs}ms): $partial",
                    )
                }

                // The first empty observation after text is the voice-end
                // signal for Whisper; freeze its timestamp for the grace clock.
                if (partial.isEmpty()) {
                    if (lastPartial.isNotEmpty() && emptySinceMs == null) emptySinceMs = now
                } else {
                    emptySinceMs = null
                    if (partial != lastPartial) partialSinceMs = now
                }

                when (
                    val decision = decidePromotion(
                        partial = partial,
                        lastPartial = lastPartial,
                        lastPartialAtMs = lastPartialAtMs,
                        emptySinceMs = emptySinceMs,
                        now = now,
                        trustVoiceEnd = config.effectiveEngine == CaptionEngineChoice.WHISPER,
                    )
                ) {
                    is PromotionDecision.Promote -> promotePartial()

                    is PromotionDecision.PromoteAndTrack -> {
                        // ATOMIC promote + track: the old flow promoted
                        // (clearing the partial) and published the new
                        // partial as two state updates, blanking the live
                        // line for a frame — the visible 'whole text
                        // flickers and comes back' during speech.
                        lastPartial = decision.promote
                        lastPartialAtMs = now
                        partialSinceMs = now
                        promoteAndTrackAtomic(decision.track, config)
                        lastPartial = decision.track
                        maybeTranslateStablePartial(decision.track, config, now)
                    }

                    is PromotionDecision.Track -> {
                        lastPartial = decision.text
                        lastPartialAtMs = now
                        publishPartial(decision.text, config)
                        maybeTranslateStablePartial(decision.text, config, now)
                    }

                    is PromotionDecision.TrackStable -> {
                        // In-window rewrite: update the tracked hypothesis,
                        // publish NOTHING (the stable-display lock holds the
                        // shown line until a strict extension arrives).
                        lastPartial = decision.text
                        lastPartialAtMs = now
                        partialSinceMs = now
                    }

                    PromotionDecision.Hold -> Unit
                }
            }
        }
    }

    private fun promotePartial() {
        val confirmed = lastPartial
        lastPartial = ""
        displayedPartial = ""  // promotion boundary: fresh utterance window
        emptySinceMs = null
        partialSinceMs = null
        if (confirmed.isEmpty()) return
        // The whisper tail-window partial can re-emit the just-promoted line
        // verbatim (publishPartial's live-feedback fallback shows it); promote
        // must never turn that echo into a duplicate history line.
        val lastLine = _state.value.history.lastOrNull()?.original
        if (confirmed == lastLine) {
            Log.i(TAG, "Promotion skipped: echo of the last history line")
            return
        }
        Log.i(TAG, "Promoted: $confirmed")
        val config = currentConfig

        val lineId = nextLineId.getAndIncrement()
        val promotedAtMs = System.currentTimeMillis()

        _state.update { current ->
            current.copy(
                history = (current.history + CaptionLine(original = confirmed, id = lineId))
                    .takeLast(MAX_HISTORY),
                partial = "",
                partialTranslation = null,
            )
        }

        // Speak the finalized line: in translate mode with a Marian-stage
        // target the TRANSLATION is the speakable output (it lands via
        // attachTranslation below); everything else speaks now.
        val marianPending = needsSecondStage(config)
        if (!marianPending) {
            speakLine(config, confirmed)
        }

        // Second-stage translation for non-English targets.
        if (needsSecondStage(config)) {
            // Reuse an already-completed opportunistic translation of the same
            // text so the native model is not run twice for one utterance.
            val cached = if (confirmed == opportunisticText) opportunisticTranslation else null
            if (cached != null) {
                opportunisticText = null
                opportunisticTranslation = null
                attachTranslation(lineId, cached, promotedAtMs)
            } else {
                val target = config.target
                translationScope.launch {
                    translateAndAttach(lineId, confirmed, target, promotedAtMs)
                }
            }
        }
    }

    /**
     * Promotes a SETTLED final from a streaming engine straight into
     * history (the streaming path has no promote/partial cycle — finals ARE
     * the utterance boundaries). Echo guard + speak + Marian kick-off
     * mirror promotePartial.
     */
    private fun promoteFinal(finalText: String, sourceLanguage: String? = null) {
        val text = finalText.trim()
        if (text.isEmpty()) return
        Log.i(TAG, "Promoted (stream): $text")
        lastActivityMs = System.currentTimeMillis()
        val config = currentConfig
        val lineId = nextLineId.getAndIncrement()
        val promotedAtMs = System.currentTimeMillis()
        _state.update { current ->
            current.copy(
                history = (current.history + CaptionLine(original = text, id = lineId))
                    .takeLast(MAX_HISTORY),
            )
        }
        enqueueLocalTranslation(lineId, text, sourceLanguage)
        val marianPending = needsSecondStage(config) || activeRoute == CaptionTranslationRoute.LOCAL_TEXT
        if (!marianPending) speakLine(config, text)
        if (needsSecondStage(config)) {
            val target = config.target
            translationScope.launch {
                translateAndAttach(lineId, text, target, promotedAtMs)
            }
        }
    }

    /**
     * Single-state promote + new-partial publish: history gains the
     * confirmed line and the live partial becomes [newPartial] in ONE
     * update, so the live line never blanks between the two (the flicker
     * users saw while text was being typed). Mirrors promotePartial's
     * echo guard and translation kick-off.
     */
    private fun promoteAndTrackAtomic(newPartial: String, config: CaptionOverlayConfig) {
        val confirmed = lastPartial
        if (confirmed.isEmpty()) {
            publishPartial(newPartial, config)
            return
        }
        val lastLine = _state.value.history.lastOrNull()?.original
        val effectiveHistory = if (confirmed == lastLine) {
            _state.value.history  // echo guard: no duplicate line
        } else {
            (_state.value.history + CaptionLine(
                original = confirmed,
                id = nextLineId.getAndIncrement(),
            )).takeLast(MAX_HISTORY)
        }
        val promoted = confirmed != lastLine
        Log.i(TAG, if (promoted) "Promoted+tracked: $confirmed" else "Tracked (echo guarded): $newPartial")
        val promotedAtMs = System.currentTimeMillis()
        // With history HIDDEN the promoted portion is invisible, so stripping
        // it from the live line would leave only a lone-word tail on screen.
        // Keep the full growing utterance instead; strip only when the faded
        // history actually shows the promoted line.
        val stripped = if (config.historyLines > 0) {
            stripPromotedPrefix(newPartial, confirmed)
        } else {
            newPartial
        }
        val echoFallback = stripped.isBlank() && newPartial.isNotBlank()
        displayedPartial = ""  // promote+track is a window boundary
        val display = stableLiveText(if (echoFallback) newPartial else stripped, displayedPartial)
        displayedPartial = display

        _state.update { current ->
            current.copy(
                history = effectiveHistory,
                partial = if (config.showPartial && !config.paused) display else "",
                partialTranslation = null,
            )
        }

        if (promoted) {
            val marianPending = needsSecondStage(config)
            if (!marianPending) speakLine(config, confirmed)
            if (needsSecondStage(config)) {
                val lineId = (effectiveHistory.lastOrNull() ?: return).id
                val cached = if (confirmed == opportunisticText) opportunisticTranslation else null
                if (cached != null) {
                    opportunisticText = null
                    opportunisticTranslation = null
                    attachTranslation(lineId, cached, promotedAtMs)
                } else {
                    val target = config.target
                    translationScope.launch {
                        translateAndAttach(lineId, confirmed, target, promotedAtMs)
                    }
                }
            }
        }
    }

    private fun translateAndAttach(
        lineId: Long,
        text: String,
        target: TranslationTarget,
        promotedAtMs: Long,
    ) {
        val generation = sessionGeneration
        val result = translationLayer.translate(text, target)
        if (generation != sessionGeneration) return
        when (result) {
            is TranslationResult.Translated -> attachTranslation(lineId, result.text, promotedAtMs)

            is TranslationResult.Unavailable -> {
                Log.w(TAG, "Translation unavailable: ${result.reason}")
                _state.update { it.copy(translationNotice = result.reason) }
            }
        }
    }

    private fun attachTranslation(lineId: Long, translated: String, promotedAtMs: Long) {
        val elapsedMs = System.currentTimeMillis() - promotedAtMs
        Log.i(TAG, "Translation attached in ${elapsedMs} ms for line #$lineId")
        _state.update { current ->
            current.copy(
                history = attachTranslationById(current.history, lineId, translated),
                translationNotice = null,
            )
        }
        if (currentConfig.speakCaptions) {
            speakLine(currentConfig, translated)
        }
    }

    /**
     * Voice output for finalized lines (device / on-device neural / BYOK
     * cloud — see CaptionSpeaker). The speaker is rebuilt when the choice
     * changes; CLOUD and NATIVE are availability-gated at construction.
     */
    private var speaker: CaptionSpeaker? = null
    private var speakerChoice: CaptionSpeakerChoice? = null

    private fun speakLine(config: CaptionOverlayConfig, text: String) {
        if (!config.speakCaptions || text.isBlank()) return
        scope.launch(Dispatchers.Default) {
            val choice = config.speakerChoice
            if (!CaptionSpeakerFactory.isAvailable(context, choice)) {
                _state.update {
                    it.copy(translationNotice = "Voice unavailable (${choice.label}) — pick another in settings")
                }
                return@launch
            }
            val current = speaker?.takeIf { speakerChoice == choice }
                ?: CaptionSpeakerFactory.create(context, choice).also {
                    speaker = it
                    speakerChoice = choice
                }
            val languageTag = if (config.mode == CaptionMode.TRANSLATE) {
                config.target.languageTag
            } else {
                config.streamLanguage.takeIf { it != "auto" } ?: "en"
            }
            Log.i(TAG, "Speaking line via ${choice.label} ($languageTag): ${text.take(60)}")
            current.speak(text, languageTag)
        }
    }

    private fun releaseSpeaker() {
        speaker?.release()
        speaker = null
        speakerChoice = null
    }

    private fun publishPartial(text: String, config: CaptionOverlayConfig) {
        if (config.showPartial && !config.paused) {
            // Never re-show words that already made it into history: the
            // whisper tail-window partial re-decodes the promoted region. If
            // stripping would blank the line entirely, keep the raw partial —
            // live feedback beats a momentary repetition (the engine-level
            // dedupe already prevents most overlaps).
            val promoted = _state.value.history.lastOrNull()?.original
            val stripped = stripPromotedPrefix(text, promoted)
            val echoFallback = stripped.isBlank() && text.isNotBlank()
            if (echoFallback) displayedPartial = ""  // tail echo re-opens the window
            val candidate = if (echoFallback) text else stripped
            // STABLE-DISPLAY LOCK: the visible line may only grow within one
            // utterance window. Held candidates emit NOTHING — no flicker.
            val display = stableLiveText(candidate, displayedPartial)
            if (display == displayedPartial && display.isNotEmpty() && candidate != display) {
                return
            }
            displayedPartial = display
            _state.update { it.copy(partial = display, partialTranslation = null) }
        }
    }

    /**
     * Opportunistically translate a partial that has stabilized (unchanged for
     * [STABLE_PARTIAL_MS]) so a translation can appear before the utterance is
     * promoted. Runs on the single-thread [translationDispatcher], so it never
     * blocks the poller and never races the native engine; history is only
     * ever written by [promotePartial], which reuses a matching result.
     */
    private fun maybeTranslateStablePartial(text: String, config: CaptionOverlayConfig, now: Long) {
        if (config.mode != CaptionMode.TRANSLATE) return
        if (!needsSecondStage(config)) return
        if (!config.showPartial || config.paused) return
        val since = partialSinceMs ?: return
        if (now - since < STABLE_PARTIAL_MS) return
        if (text == opportunisticText) return
        opportunisticText = text
        opportunisticTranslation = null
        val target = config.target
        translationScope.launch {
            when (val result = translationLayer.translate(text, target)) {
                is TranslationResult.Translated -> {
                    opportunisticTranslation = result.text
                    _state.update { current ->
                        if (current.partial == text) current.copy(partialTranslation = result.text) else current
                    }
                }

                is TranslationResult.Unavailable -> {
                    Log.w(TAG, "Partial translation unavailable: ${result.reason}")
                }
            }
        }
    }

    private fun resetOpportunistic() {
        opportunisticText = null
        opportunisticTranslation = null
    }

    /**
     * Stage captured PCM for the ordered consumer. Called from the capture
     * thread; never blocks on inference. Backpressure: if the consumer falls
     * behind, the chunk is dropped so latency stays live (captions must not
     * lag behind the video).
     */
    fun pushAudio(pcm: ShortArray, sampleCount: Int) {
        if (sampleCount <= 0 || sampleCount > pcm.size || stopping || currentConfig.paused) return
        val speech = localSpeech
        if (speech != null) {
            // Bypass the legacy DROP_OLDEST channel: preserve every accepted sample.
            // LiveSpeechProcessor owns a bounded queue and the poller reports overload.
            speech.tryAccept(if (sampleCount == pcm.size) pcm else pcm.copyOf(sampleCount))
        } else if (engine != null) audioChannel.trySend(pcm.copyOf(sampleCount))
    }

    fun markCaptureSilent(silent: Boolean) {
        val status = when {
            silent && _state.value.status == Status.RUNNING -> Status.CAPTURE_SILENT
            !silent && _state.value.status == Status.CAPTURE_SILENT -> Status.RUNNING
            else -> _state.value.status
        }
        _state.update { current ->
            if (current.status == Status.ERROR) current else current.copy(status = status)
        }
    }

    /** Playback pauses belong to the same audio session and must never reconnect it. */
    fun onCaptureResumed() { markCaptureSilent(false) }

    fun clearTranscript() {
        displayedPartial = ""
        lastPartial = ""
        emptySinceMs = null
        partialSinceMs = null
        resetOpportunistic()
        _state.value = _state.value.copy(history = emptyList(), partial = "", partialTranslation = null)
        if (activeRoute == CaptionTranslationRoute.LOCAL_TEXT) configureLocalTranslation(currentConfig)
    }

    fun stop(onDrained: (() -> Unit)? = null) {
        if (stopping) return
        stopping = true
        // DRAIN, don't drop: the audio source has ended, but the provider is
        // still processing the buffered tail. Keep the poller alive so the
        // trailing words keep landing, tell the engine the capture ended
        // (OpenAI commits its buffer, Deepgram sends CloseStream), and only
        // tear down once arrivals go quiet (or the hard cap hits).
        val gen = sessionGeneration
        val engineAtStop = engine
        localSpeech?.finish()
        (engineAtStop as? com.sal7one.transiber.byok.StreamingCloudEngine)
            ?.onCaptureEnded()
        scope.launch {
            val deadline = System.currentTimeMillis() + DRAIN_MAX_MS
            while (System.currentTimeMillis() - lastActivityMs < DRAIN_QUIET_MS &&
                System.currentTimeMillis() < deadline
            ) {
                delay(400)
            }
            if (gen != sessionGeneration) return@launch  // a new session took over
            stopInternal(promotePending = true)
            _state.value = _state.value.copy(status = Status.STOPPED)
            onDrained?.invoke()
        }
    }

    private fun stopInternal(promotePending: Boolean) {
        ++translationGeneration
        val oldBridge = localTranslationBridge
        localTranslationBridge = null
        oldBridge?.close()
        val pendingCleanup = translationCleanup
        translationCleanup = cleanupScope.launch { pendingCleanup?.join(); oldBridge?.awaitClosed() }
        translationScope.cancel()
        pollJob?.cancel()
        pollJob = null
        audioJob?.cancel()
        audioJob = null
        val stale = engine
        val staleSpeech = localSpeech
        engine = null
        localSpeech = null
        if (promotePending) promotePartial()
        if (stale != null || staleSpeech != null) {
            engineCleanup = cleanupScope.launch { try { stale?.release() } finally { staleSpeech?.close() } }
        }
        // Release the ~120 MB translation engine when the session stops.
        // Off the caller thread (stop()/teardown can arrive on Main) and the
        // native side keeps the engine alive until an in-flight translate
        // finishes (shared_ptr registry), so this never blocks on a decode.

        releaseSpeaker()
    }

    fun shutdown() {
        ++sessionGeneration
        stopInternal(promotePending = false)
        audioChannel.close()
        cleanupScope.launch { onnxTranslator.release() }
        if (externalScope == null) scope.cancel()
    }

    companion object {
        private const val TAG = "CaptionEngine"
        const val SAMPLE_RATE = 16_000
        private const val MAX_HISTORY = CaptionReading.MAX_HISTORY
        // Source-stop drain: keep the session alive until caption arrivals
        // are quiet for DRAIN_QUIET_MS (or the hard cap) so trailing
        // in-flight words from the provider are never dropped.
        private const val DRAIN_QUIET_MS = 3_000L
        private const val DRAIN_MAX_MS = 15_000L
    }
}

/**
 * The caption content a session shell renders once it has something to show:
 * finalized utterances plus the live partial. Status/error/engine plumbing
 * stays on [CaptionEngineController.State].
 */
data class CaptionSessionContent(
    val history: List<CaptionLine> = emptyList(),
    val partial: String = "",
)

/**
 * The one derived shell state (utils-bible: one convention) for the live
 * caption overlay session. Pure fold of [CaptionEngineController.State],
 * mapped conservatively, precedence:
 *
 * 1. Error: non-blank error field wins over everything (Status.ERROR always
 *    sets one at engine load failure; a blank/null message on an ERROR status
 *    still must not render as content — fallback reason supplied).
 * 2. Loading while actively listening/recording — RUNNING and CAPTURE_SILENT
 *    are one live session — plus LOADING_MODEL. The "Listening…" wording is
 *    label-available-separately (engineLabel/partial on State), since
 *    ScreenState.Loading carries none.
 * 3. Empty only when idle/stopped AND no transcript content remains.
 * 4. Ready otherwise: any residual content after STOPPED (drained tail)
 *    stays viewable instead of being hidden behind Loading/Empty.
 */
fun captionScreenState(state: CaptionEngineController.State): ScreenState<CaptionSessionContent> = when {
    state.status == CaptionEngineController.Status.ERROR || !state.error.isNullOrBlank() ->
        ScreenState.Error(
            state.error?.trim()?.takeIf { it.isNotEmpty() } ?: "Caption engine failed",
        )
    state.status == CaptionEngineController.Status.LOADING_MODEL ||
        state.status == CaptionEngineController.Status.RUNNING ||
        state.status == CaptionEngineController.Status.CAPTURE_SILENT -> ScreenState.Loading
    state.history.isEmpty() && state.partial.isBlank() -> ScreenState.Empty
    else -> ScreenState.Ready(
        CaptionSessionContent(history = state.history, partial = state.partial),
    )
}
