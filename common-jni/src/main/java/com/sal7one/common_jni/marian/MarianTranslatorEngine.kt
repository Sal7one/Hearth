package com.sal7one.common_jni.marian

import java.io.Closeable

/**
 * Public Kotlin facade over the native Marian (OPUS-MT) translation engine.
 *
 * Create an engine with [create], translate finalized caption text with
 * [translate], and release the ~120 MB of native memory with [close].
 * Translation is blocking — call it off the main thread (the caption
 * controller already uses a dedicated single-thread dispatcher).
 */
class MarianTranslatorEngine private constructor(
    private val handle: Long,
) : Closeable {

    sealed interface Outcome {
        /** [latencyMs] covers tokenization + encoder + greedy decode. */
        data class Translated(val text: String, val latencyMs: Long) : Outcome

        /** [reason] is human-readable and safe to show in the overlay. */
        data class Failure(val reason: String) : Outcome
    }

    fun translate(text: String): Outcome {
        if (handle == 0L) return Outcome.Failure("Translation engine is not loaded.")
        return try {
            val translated = MarianNative.nativeTranslate(handle, text)
            if (translated == null) {
                Outcome.Failure(
                    MarianNative.nativeGetLastError()
                        ?: "Translation failed for an unknown reason.",
                )
            } else {
                Outcome.Translated(translated, MarianNative.nativeLastLatencyMs(handle))
            }
        } catch (e: LinkageError) {
            Outcome.Failure("Translation runtime is not available in this build.")
        } catch (e: Exception) {
            Outcome.Failure("Translation failed: ${e.message ?: "unknown error"}")
        }
    }

    override fun close() {
        if (handle != 0L) {
            MarianNative.nativeRelease(handle)
        }
    }

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
        fun create(modelDir: String): MarianTranslatorEngine? {
            val handle = MarianNative.nativeCreate(modelDir)
            return if (handle == 0L) null else MarianTranslatorEngine(handle)
        }
    }
}
