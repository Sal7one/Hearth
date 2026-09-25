package com.sal7one.transiber.sign

import com.sal7one.common_jni.sign.SignHands
import com.sal7one.transiber.runtime.LocalWorkGate
import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live overlay state. Everything the bubble and the sign page need; errors verbatim. */
internal data class SignUiState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val paused: Boolean = false,
    /** Honest engine description, e.g. "ONNX ASL alphabet (English letters)" or "Geometric fallback (ASL letter subset)". */
    val engineLabel: String = "",
    /** Honest catalog name of the fingerspelling language (domain data, not translated UI copy). */
    val languageLabel: String = "",
    /** Latest single-frame hypothesis; shown large while not yet committed. */
    val currentLetter: String? = null,
    /** Consensus letters, appended exactly once per emission. */
    val buildingText: String = "",
    /** Completed text rolled out of [buildingText]; capped at [HISTORY_MAX_CHARS]. */
    val history: String = "",
    val handPresent: Boolean = false,
    val fps: Float = 0f,
    val lastError: String? = null,
)

/**
 * Orchestrates one live fingerspelling session: native hand landmarks
 * ([SignHands]) → [SignFrameDecoder] → the sign package's classifier
 * ([OnnxLandmarkClassifier] or the [GeometricFingerspelling] fallback) +
 * [SignSequenceBuffer]/[StableSignDetector] consensus → [SignUiState].
 *
 * Frames arrive synchronously on the camera analyzer thread through
 * [offerFrame]; the busy flag, the generation counter and the ~150 ms
 * throttle keep at most one inference per frame interval, mirroring the
 * camera OCR frame guards. Native models are opened on the session worker,
 * held while it parks, and released in a [NonCancellable] finally block
 * together with the [LocalWorkGate] lease — a cancelled stop still frees
 * every native handle before another workload may start (CameraOcrController
 * / SpeechRuntime cleanup pattern).
 */
internal class SignEngineController(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SignUiState())
    val state: StateFlow<SignUiState> = mutable

    /** One inference at a time on the analyzer thread; late frames are dropped, never queued. */
    private val busy = AtomicBoolean(false)
    /** Bumped by every session/pause change; stale in-flight results are discarded. */
    private val generation = AtomicLong(0L)
    private var job: Job? = null
    private var lastFrameMs = 0L
    private var lastProcessedMs = 0L
    private var fpsEma = 0f

    @Volatile private var hands: SignHands? = null
    @Volatile private var classifier: SignClassifier? = null
    @Volatile private var classifierCloser: AutoCloseable? = null
    private var buffer = SignSequenceBuffer()
    private var stable = StableSignDetector()

    /**
     * Starts one session with the persisted classifier selection (or the
     * only complete installed one); with no installed classifier it falls
     * back to the geometric ASL letter subset, stated honestly in the state.
     */
    fun start() {
        if (job != null) return
        generation.incrementAndGet()
        buffer = SignSequenceBuffer()
        stable = StableSignDetector()
        lastFrameMs = 0L
        lastProcessedMs = 0L
        fpsEma = 0f
        mutable.value = SignUiState(running = true, loading = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: LocalWorkGate.Lease? = null
            try {
                lease = LocalWorkGate.acquire(LEASE_OWNER)
                val loaded = withContext(Dispatchers.IO + NonCancellable) { open() }
                ensureActive()
                hands = loaded.hands
                classifier = loaded.classifier
                classifierCloser = loaded.classifierCloser
                mutable.update {
                    it.copy(loading = false, engineLabel = loaded.engineLabel, languageLabel = loaded.language.displayName)
                }
                // Park until stop()/failure cancels this worker; frames are
                // processed on the analyzer thread against the loaded models.
                Job().join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(lastError = e.message ?: e.toString()) }
            } finally {
                // Native teardown outlives cancellation exactly like SpeechRuntime cleanup.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        classifierCloser?.runCatching { close() }
                        classifierCloser = null
                        classifier = null
                        hands?.runCatching { close() }
                        hands = null
                    }
                    lease?.close()
                    mutable.update {
                        it.copy(running = false, loading = false, paused = false, handPresent = false, fps = 0f)
                    }
                    job = null
                }
            }
        }
        job?.start()
    }

    /** One loaded session; [engineLabel] states the honest fallback when no ONNX model is installed. */
    private class Loaded(
        val hands: SignHands,
        val classifier: SignClassifier,
        val classifierCloser: AutoCloseable?,
        val engineLabel: String,
        val language: SignLanguage,
    )

    private fun open(): Loaded {
        val handsDir = handModelDir(appContext)
            ?: error("Hand landmark models are not installed. Import hand_detector.onnx and hand_landmarks_detector.onnx in Models.")
        val pipeline = SignHands(handsDir, threads = 2)
        try {
            val chosen = selectedClassifier(appContext) ?: availableClassifiers(appContext).firstOrNull()
            if (chosen != null) {
                val onnx = OnnxLandmarkClassifier.load(chosen.file, chosen.labels, threads = 2)
                return Loaded(pipeline, onnx, onnx, "ONNX ${chosen.language.displayName}", chosen.language)
            }
            // No trained classifier installed: only the geometric letter subset exists, only for ASL.
            return Loaded(pipeline, GeometricFingerspelling(), null, "Geometric fallback (ASL letter subset)", SignLanguage.ASL_ENGLISH)
        } catch (e: Exception) {
            pipeline.close()
            throw e
        }
    }

    /**
     * Cheap pre-check for the camera loop: true when [offerFrame] would
     * actually run inference right now. Called before any YUV conversion
     * so skipped frames cost nothing.
     */
    fun readyForFrame(): Boolean {
        val current = mutable.value
        if (!current.running || current.loading || current.paused) return false
        if (System.currentTimeMillis() - lastProcessedMs < FRAME_INTERVAL_MS) return false
        return !busy.get()
    }

    /**
     * Frame entry from the camera analyzer thread. [frame] is a tightly
     * packed direct RGBA buffer of [width] x [height] owned by the caller;
     * it is fully consumed before returning.
     */
    fun offerFrame(frame: ByteBuffer, width: Int, height: Int, timestampMs: Long) {
        val epoch = generation.get()
        val now = System.currentTimeMillis()
        if (now - lastProcessedMs < FRAME_INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return
        try {
            val current = mutable.value
            if (!current.running || current.loading || current.paused) return
            val pipeline = hands ?: return
            val engine = classifier ?: return
            if (generation.get() != epoch) return
            lastProcessedMs = now
            val detected = pipeline.detect(frame, width, height)
            val observation = SignFrameDecoder.fromDetectedHands(
                detected,
                timestampMs = timestampMs,
                minHandScore = MIN_HAND_SCORE,
            )
            if (generation.get() != epoch) return
            buffer.push(observation)
            val window = buffer.window()
            val hypothesis = engine.classify(window)
            // Exact-once: onHypothesis returns the consensus label a single
            // time per agreement run; this append is the only writer.
            val emitted = stable.onHypothesis(hypothesis, window.size)
            if (lastFrameMs > 0) {
                val instant = 1000f / (now - lastFrameMs).coerceAtLeast(1)
                fpsEma = if (fpsEma == 0f) instant else fpsEma * .8f + instant * .2f
            }
            lastFrameMs = now
            mutable.update { value ->
                var building = value.buildingText
                var history = value.history
                if (emitted != null) building += emitted
                if (building.length > BUILDING_MAX_CHARS) {
                    history = (history + building.substring(0, building.length - BUILDING_MAX_CHARS))
                        .takeLast(HISTORY_MAX_CHARS)
                    building = building.takeLast(BUILDING_MAX_CHARS)
                }
                value.copy(
                    currentLetter = hypothesis?.label,
                    buildingText = building,
                    history = history,
                    handPresent = observation.hands.isNotEmpty(),
                    fps = fpsEma,
                )
            }
        } catch (e: Exception) {
            // Surface the engine failure verbatim and end the session; never
            // convert a broken pipeline into empty detections.
            mutable.update { it.copy(lastError = e.message ?: e.toString()) }
            stop()
        } finally {
            busy.set(false)
        }
    }

    fun pause() {
        if (job == null) return
        generation.incrementAndGet()
        buffer.clear()
        stable.reset()
        mutable.update { it.copy(paused = true, currentLetter = null, handPresent = false, fps = 0f) }
    }

    fun resume() {
        if (job == null) return
        lastProcessedMs = 0L
        mutable.update { it.copy(paused = false) }
    }

    fun clearText() {
        generation.incrementAndGet()
        buffer.clear()
        stable.reset()
        mutable.update { it.copy(currentLetter = null, buildingText = "", history = "") }
    }

    fun stop() {
        if (job == null) return
        generation.incrementAndGet()
        job?.cancel()
    }

    /** Wait until native handles and the lease are released; safe after cancellation. */
    suspend fun awaitReleased() {
        job?.join()
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val LEASE_OWNER = "Sign language"
        /** ~6.5 inference passes per second keep consensus responsive on mid-range phones. */
        internal const val FRAME_INTERVAL_MS = 150L
        /** Palm/hand presence gate; 0.5 matches the MediaPipe default used by the decoder. */
        internal const val MIN_HAND_SCORE = 0.5f
        private const val BUILDING_MAX_CHARS = 120
        internal const val HISTORY_MAX_CHARS = 200
    }
}
