package com.sal7one.common_jni.marian

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * Public Kotlin facade over the native Marian (OPUS-MT) translation engine.
 *
 * Create an engine with [create], translate finalized caption text with
 * [translate], and release the ~120 MB of native memory with [close].
 * Translation is blocking — call it off the main thread (the caption
 * controller already uses a dedicated single-thread dispatcher).
 */
class MarianTranslatorEngine private constructor(
    initialHandle: Long,
) : Closeable {
    private val handle = AtomicLong(initialHandle)

    sealed interface Outcome {
        /** [latencyMs] covers tokenization + encoder + greedy decode. */
        data class Translated(val text: String, val latencyMs: Long, val stages: StageStats?) : Outcome

        /** [reason] is human-readable and safe to show in the overlay. */
        data class Failure(val reason: String) : Outcome
    }

    data class StageStats(val tokenizeMs: Long, val encoderMs: Long, val decoderMs: Long,
                          val tokensDecoded: Long, val totalMs: Long)

    data class RuntimeOptions(val threads: Int = 4, val maxOutputTokens: Int = 128, val deadlineMs: Int = 20_000) {
        init {
            require(threads in 1..8) { "Marian threads must be 1–8" }
            require(maxOutputTokens in 1..384) { "Marian output limit must be 1–384 tokens" }
            require(deadlineMs in 100..60_000) { "Marian deadline must be 100–60000 ms" }
        }
    }

    fun translate(text: String): Outcome {
        val current = handle.get()
        if (current == 0L) return Outcome.Failure("Translation engine is not loaded.")
        return try {
            val translated = MarianNative.nativeTranslate(current, text)
            if (translated == null) {
                Outcome.Failure(
                    MarianNative.nativeGetLastError()
                        ?: "Translation failed for an unknown reason.",
                )
            } else {
                val nativeStats = MarianNative.nativeLastStats(current)
                val stages = nativeStats?.takeIf { it.size == 5 }?.let {
                    StageStats(it[0], it[1], it[2], it[3], it[4])
                }
                Outcome.Translated(translated, MarianNative.nativeLastLatencyMs(current), stages)
            }
        } catch (e: LinkageError) {
            Outcome.Failure("Translation runtime is not available in this build.")
        } catch (e: Exception) {
            Outcome.Failure("Translation failed: ${e.message ?: "unknown error"}")
        }
    }

    fun cancel() { handle.get().takeIf { it != 0L }?.let(MarianNative::nativeCancel) }

    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(MarianNative::nativeRelease) }

    companion object {
        /** True when the native Marian runtime is compiled into this build. */
        val isRuntimeAvailable: Boolean
            get() = try {
                MarianNative.nativeRuntimeAvailable()
            } catch (e: LinkageError) {
                false
            }

        /** Last native error message, for diagnostics. */
        fun lastError(): String? = MarianNative.nativeGetLastError()

        /**
         * Loads an engine from a model directory containing source.spm,
         * tokenizer.json, encoder_model*.onnx and decoder_model_merged*.onnx.
         * Returns null (with [lastError] set) on failure.
         */
        fun create(modelDir: String, options: RuntimeOptions = RuntimeOptions()): MarianTranslatorEngine? {
            val handle = MarianNative.nativeCreateConfigured(modelDir, options.threads, options.maxOutputTokens, options.deadlineMs)
            return if (handle == 0L) null else MarianTranslatorEngine(handle)
        }
    }
}
