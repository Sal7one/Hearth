@file:Suppress("DEPRECATION")

package com.sal7one.common_jni

import android.content.Context
import android.util.Log
import com.sal7one.common_jni.config.AudioGateConfig
import com.sal7one.common_jni.config.ProcessingConfig
import com.sal7one.common_jni.core.ModelLoader
import com.sal7one.common_jni.core.VadConfig
import com.sal7one.common_jni.core.VadDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import kotlin.jvm.Throws

/**
 * common-jni entry point.
 *
 * Responsibilities:
 *  - Load the native library exactly once ([init]) and register the log callback.
 *  - Expose capability probes (`hasXxx`) backed by the native capability mask.
 *  - Forward thin wrappers to the FFmpeg utility layer.
 *
 * Everything STT-related lives in [com.sal7one.common_jni.engine.SttEngine] and
 * its concrete impls — they own their own JNI handles. Do **not** add
 * per-engine `nativeXxxWhisper` / `nativeXxxVosk` shims back into this object:
 * we had two parallel copies before, which is what this rewrite is removing.
 */
object CommonJni {
    private const val TAG = "CommonJni"
    private const val GLOBAL_CONFIG_UNSUPPORTED_MESSAGE =
        "Global processing and audio-gate configuration is unsupported; " +
            "configure each STT session with SttConfig or each VAD detector with VadConfig"
    private const val ENGINE_WARM_UP_UNSUPPORTED_MESSAGE =
        "Global engine warm-up is unsupported; load and prepare the selected model in its owning session"
    private const val GENERIC_AUDIO_PROCESSING_UNSUPPORTED_MESSAGE =
        "Generic processAudioBuffer was a validation/copy shim, not an audio processor; " +
            "use the typed AudioUtils operations or an engine-specific direct-buffer API"

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null

    // =========================================================================
    // Log callback
    // =========================================================================

    data class LogEntry(
        val level: Int,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _logs = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logs: Flow<LogEntry> = _logs.asSharedFlow()

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Load the native library, register the log callback, and cache the
     * application context. Safe to call multiple times — only the first call
     * does real work.
     *
     * @throws IllegalStateException if `System.loadLibrary("common_jni")`
     *         fails (missing ABI, stripped so, etc.)
     */
    @Throws(IllegalStateException::class)
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            try {
                System.loadLibrary("common_jni")
                nativeInit()
                nativeSetLogCallback(
                    NativeLogCallback { level, tag, message ->
                        _logs.tryEmit(LogEntry(level, tag, message))
                    },
                )
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("Failed to load native library: ${e.message}", e)
            }
            initialized = true
            Log.i(
                TAG,
                "CommonJni initialized: FFmpeg=${hasFFmpeg()}, Whisper=${hasWhisper()}, " +
                    "Vosk=${hasVosk()}, Onnx=${hasOnnx()}, OpenCV=${hasOpenCV()}",
            )
        }
    }

    fun isInitialized(): Boolean = initialized

    // =========================================================================
    // Capability probes
    // =========================================================================

    fun hasFFmpeg(): Boolean = initialized && nativeHasFFmpeg()
    fun hasWhisper(): Boolean = initialized && nativeHasWhisper()
    fun hasVosk(): Boolean = initialized && nativeHasVosk()
    fun hasOnnx(): Boolean = initialized && nativeHasOnnx()
    fun hasOpenCV(): Boolean = initialized && nativeHasOpenCV()
    fun hasSign(): Boolean = initialized && nativeHasSign()

    fun getVersion(): String = if (initialized) nativeGetVersion() else "not initialized"
    fun getCapabilityMask(): Int = if (initialized) nativeGetCapabilityMask() else 0

    // =========================================================================
    // FFmpeg — blocking API
    // =========================================================================
    //
    // All methods here are thin native forwards. Callers should already be on
    // an IO-appropriate dispatcher. If you need coroutine wrappers, use the
    // `*Async` helpers below; they are just `withContext(Dispatchers.IO) { … }`.

    fun decodeAudio(filePath: String, targetSampleRate: Int = 16_000): ShortArray? {
        if (!hasFFmpeg()) return null
        return nativeDecodeAudio(filePath, targetSampleRate)
    }

    fun extractAudio(
        inputPath: String,
        outputPath: String,
        format: String,
        bitrateKbps: Int = 0,
    ): Boolean = hasFFmpeg() && nativeExtractAudio(inputPath, outputPath, format, bitrateKbps)

    /**
     * Stream-copy-first audio extractor. Tries container remux when the
     * source codec already matches [format]; falls back to full transcode.
     * Typically 10–50× faster than unconditional transcoding.
     */
    fun extractAudioSmart(
        inputPath: String,
        outputPath: String,
        format: String,
        targetSampleRate: Int = 0,
        bitrateKbps: Int = 0,
    ): Boolean = hasFFmpeg() &&
        nativeExtractAudioSmart(inputPath, outputPath, format, targetSampleRate, bitrateKbps)

    /**
     * Smart extraction with real native read progress and cooperative
     * cancellation. The callback runs on the calling thread; keep it fast and
     * return false when the owning operation has been cancelled.
     */
    fun extractAudioWithProgress(
        inputPath: String,
        outputPath: String,
        format: String,
        targetSampleRate: Int = 0,
        bitrateKbps: Int = 0,
        allowStreamCopy: Boolean = false,
        onProgress: AudioExtractionProgressCallback,
    ): Boolean = hasFFmpeg() && nativeExtractAudioWithProgress(
        inputPath,
        outputPath,
        format,
        targetSampleRate,
        bitrateKbps,
        allowStreamCopy,
        onProgress,
    )

    fun canStreamCopy(
        inputPath: String,
        targetFormat: String,
        targetSampleRate: Int = 0,
    ): Boolean = hasFFmpeg() && nativeCanStreamCopy(inputPath, targetFormat, targetSampleRate)

    fun getFileDuration(filePath: String): Long =
        if (hasFFmpeg()) nativeGetFileDuration(filePath) else -1L

    fun getFileInfo(filePath: String): String? =
        if (hasFFmpeg()) nativeGetFileInfo(filePath) else null

    fun hasEncoder(name: String): Boolean = hasFFmpeg() && nativeHasEncoder(name)

    // =========================================================================
    // FFmpeg — suspending wrappers
    // =========================================================================

    @Throws(IOException::class)
    suspend fun decodeAudioAsync(filePath: String, targetSampleRate: Int = 16_000): ShortArray {
        check(hasFFmpeg()) { "FFmpeg not available" }
        return withContext(Dispatchers.IO) {
            nativeDecodeAudio(filePath, targetSampleRate)
                ?: throw IOException(getLastError() ?: "Decode failed")
        }
    }

    @Throws(IOException::class)
    suspend fun extractAudioAsync(
        inputPath: String,
        outputPath: String,
        format: String,
        bitrateKbps: Int = 0,
    ) {
        check(hasFFmpeg()) { "FFmpeg not available" }
        withContext(Dispatchers.IO) {
            if (!nativeExtractAudio(inputPath, outputPath, format, bitrateKbps)) {
                throw IOException(getLastError() ?: "Extraction failed")
            }
        }
    }

    @Throws(IOException::class)
    suspend fun extractAudioSmartAsync(
        inputPath: String,
        outputPath: String,
        format: String,
        targetSampleRate: Int = 0,
        bitrateKbps: Int = 0,
    ) {
        check(hasFFmpeg()) { "FFmpeg not available" }
        withContext(Dispatchers.IO) {
            if (!nativeExtractAudioSmart(inputPath, outputPath, format, targetSampleRate, bitrateKbps)) {
                throw IOException(getLastError() ?: "Smart extraction failed")
            }
        }
    }

    // =========================================================================
    // Developer configuration
    // =========================================================================

    /**
     * Retained as a throwing ABI shim for callers compiled against older releases.
     * Configure VAD per detector with [VadConfig] or per STT session with `SttConfig`.
     */
    @Deprecated(
        message = "Global audio-gate configuration is unsupported; use SttConfig or VadConfig",
        level = DeprecationLevel.ERROR,
    )
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    fun setAudioGateConfig(config: AudioGateConfig) {
        throw UnsupportedOperationException(GLOBAL_CONFIG_UNSUPPORTED_MESSAGE)
    }

    /**
     * Retained as a throwing ABI shim for callers compiled against older releases.
     * Runtime options belong to the engine/session that consumes them.
     */
    @Deprecated(
        message = "Global processing configuration is unsupported; use per-session SttConfig or VadConfig",
        level = DeprecationLevel.ERROR,
    )
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    fun setProcessingConfig(config: ProcessingConfig) {
        throw UnsupportedOperationException(GLOBAL_CONFIG_UNSUPPORTED_MESSAGE)
    }

    /** Returns an explicit unsupported compatibility document; no settings are applied. */
    @Deprecated(
        message = "The process-wide audio gate is unsupported; inspect the active engine or VadDetector",
        level = DeprecationLevel.WARNING,
    )
    fun getAudioGateStatus(): String? = if (initialized) nativeGetAudioGateStatus() else null
    fun getBufferPoolStats(): String? = if (initialized) nativeGetBufferPoolStats() else null

    /**
     * Retained as a throwing suspend-ABI shim for callers compiled against older releases.
     * A model-owning session is the only place a backend can be prepared truthfully.
     */
    @Deprecated(
        message = "Global engine warm-up is unsupported; prepare the selected model in its session",
        level = DeprecationLevel.ERROR,
    )
    suspend fun warmUpEngines() {
        throw UnsupportedOperationException(ENGINE_WARM_UP_UNSUPPORTED_MESSAGE)
    }

    // =========================================================================
    // VAD / models
    // =========================================================================

    fun createVadDetector(config: VadConfig = VadConfig()): VadDetector = VadDetector(config)
    fun getModelLoader(): ModelLoader? = appContext?.let(::ModelLoader)

    /** App-owned roots used to keep path-reopening model parsers off shared storage. */
    internal fun getPrivateModelRoots(): List<File> = appContext?.let { context ->
        listOf(context.filesDir, context.cacheDir)
    } ?: emptyList()

    /** Dedicated private staging root for model backends that can only reopen paths. */
    internal fun getModelStagingRoot(): File? = appContext?.let { context ->
        File(context.cacheDir, "common-jni-models")
    }

    // =========================================================================
    // Session-wide control + error state
    // =========================================================================

    fun cancelAll() { if (initialized) nativeCancelAll() }
    fun getLastError(): String? = if (initialized) nativeGetLastError() else null
    fun clearError() { if (initialized) nativeClearError() }

    // =========================================================================
    // Direct-buffer audio processing (zero-copy)
    // =========================================================================

    fun getDecodedSampleCount(filePath: String, targetSampleRate: Int = 16_000): Int =
        if (initialized) nativeGetDecodedSampleCount(filePath, targetSampleRate) else -1

    @Throws(IllegalArgumentException::class)
    fun decodeAudioToBuffer(filePath: String, targetSampleRate: Int, outputBuffer: ByteBuffer): Boolean {
        require(outputBuffer.isDirect) { "outputBuffer must be a direct ByteBuffer" }
        return initialized && nativeDecodeAudioToBuffer(filePath, targetSampleRate, outputBuffer)
    }

    /**
     * Retained as a throwing ABI shim. The former implementation only copied
     * bytes and could not truthfully promise processing semantics.
     */
    @Deprecated(
        message = "Generic audio processing is unsupported; use a typed or engine-specific API",
        level = DeprecationLevel.ERROR,
    )
    @Suppress("UNUSED_PARAMETER")
    fun processAudioBuffer(
        inputBuffer: ByteBuffer,
        sampleCount: Int,
        sampleRate: Int,
        outputBuffer: ByteBuffer? = null,
    ): Boolean {
        throw UnsupportedOperationException(GENERIC_AUDIO_PROCESSING_UNSUPPORTED_MESSAGE)
    }

    /**
     * Normalised RMS audio level (0..1) of a slice of a direct int16 PCM
     * [ByteBuffer]. NEON-accelerated in the native layer; safe to call at
     * mic-frame cadence.
     */
    @Throws(IllegalArgumentException::class)
    fun audioLevel(
        buffer: ByteBuffer,
        byteOffset: Int = 0,
        byteCount: Int = buffer.remaining(),
    ): Float {
        require(buffer.isDirect) { "buffer must be a direct ByteBuffer" }
        if (!initialized) return 0f
        return nativeAudioLevelDirect(buffer, byteOffset, byteCount)
    }

    fun isBufferAligned(buffer: ByteBuffer): Boolean =
        buffer.isDirect && initialized && nativeIsBufferAligned(buffer)

    /**
     * Round a sample count up to a cache-line-aligned size (64 B) suitable
     * for the NEON audio paths, with a 1024-sample floor. Pure math — no
     * native call — so cheap to use on every allocation decision.
     */
    fun getRecommendedBufferSize(minSamples: Int): Int {
        require(minSamples >= 0) { "minSamples must be non-negative" }
        val aligned = ((minSamples.toLong() + 63L) / 64L) * 64L
        require(aligned <= Int.MAX_VALUE) { "minSamples is too large to align safely" }
        return aligned.toInt().coerceAtLeast(1024)
    }

    // =========================================================================
    // Native declarations
    // =========================================================================
    //
    // These are registered by RegisterNatives in common_jni_bridge.cpp — the
    // ordering and signatures here must match gCommonJniMethods[]. Per-engine
    // (Whisper/Vosk) bindings deliberately do NOT live here; they live on
    // their own Kotlin classes under engine/{whisper,vosk} so each engine
    // owns its JNI surface and handles independently.

    private external fun nativeInit()
    private external fun nativeSetLogCallback(callback: NativeLogCallback)
    private external fun nativeGetVersion(): String
    private external fun nativeGetCapabilityMask(): Int
    private external fun nativeHasFFmpeg(): Boolean
    private external fun nativeHasWhisper(): Boolean
    private external fun nativeHasVosk(): Boolean
    private external fun nativeHasOnnx(): Boolean
    private external fun nativeHasOpenCV(): Boolean
    private external fun nativeHasSign(): Boolean

    private external fun nativeDecodeAudio(filePath: String, targetSampleRate: Int): ShortArray?
    private external fun nativeExtractAudio(
        inputPath: String,
        outputPath: String,
        format: String,
        bitrateKbps: Int,
    ): Boolean
    private external fun nativeExtractAudioSmart(
        inputPath: String,
        outputPath: String,
        format: String,
        targetSampleRate: Int,
        bitrateKbps: Int,
    ): Boolean
    private external fun nativeExtractAudioWithProgress(
        inputPath: String,
        outputPath: String,
        format: String,
        targetSampleRate: Int,
        bitrateKbps: Int,
        allowStreamCopy: Boolean,
        onProgress: AudioExtractionProgressCallback,
    ): Boolean
    private external fun nativeCanStreamCopy(
        inputPath: String,
        targetFormat: String,
        targetSampleRate: Int,
    ): Boolean
    private external fun nativeGetFileDuration(filePath: String): Long
    private external fun nativeGetFileInfo(filePath: String): String?
    private external fun nativeHasEncoder(name: String): Boolean

    private external fun nativeCancelAll()
    private external fun nativeGetLastError(): String?
    private external fun nativeClearError()

    private external fun nativeDecodeAudioToBuffer(
        filePath: String,
        targetSampleRate: Int,
        outputBuffer: ByteBuffer,
    ): Boolean
    private external fun nativeGetDecodedSampleCount(filePath: String, targetSampleRate: Int): Int
    private external fun nativeProcessAudioBuffer(
        inputBuffer: ByteBuffer,
        sampleCount: Int,
        sampleRate: Int,
        outputBuffer: ByteBuffer?,
    ): Boolean
    private external fun nativeAudioLevelDirect(
        buffer: ByteBuffer,
        byteOffset: Int,
        byteCount: Int,
    ): Float
    private external fun nativeIsBufferAligned(buffer: ByteBuffer): Boolean

    private external fun nativeSetAudioGateConfig(configJson: String)
    private external fun nativeSetProcessingConfig(configJson: String)
    private external fun nativeGetAudioGateStatus(): String?
    private external fun nativeGetBufferPoolStats(): String?
    private external fun nativeWarmUpEngines()
}
