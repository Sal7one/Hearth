package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopQwenNative
import java.nio.file.Path

/**
 * Qwen3-ASR over the shared Android sherpa backend.
 *
 * One native recognizer owns one pinned model directory. Calls are serialized by the native
 * lease registry; closing retires the handle and waits for an in-flight decode to finish.
 */
internal class DesktopQwenSpeechEngine private constructor(
    private var handle: Long,
    val executionInfo: String,
) : AutoCloseable {
    fun transcribe(pcm: FloatArray): String {
        require(pcm.isNotEmpty()) { "Qwen3-ASR requires non-empty 16 kHz audio" }
        val active = synchronized(this) { handle }
        check(active != 0L) { "Qwen3-ASR session is closed" }
        return DesktopQwenNative.transcribe(active, pcm).toString(Charsets.UTF_8).trim()
    }

    fun language(): String {
        val active = synchronized(this) { handle }
        if (active == 0L) return ""
        return DesktopQwenNative.language(active).toString(Charsets.UTF_8)
    }

    /** Ask the wrapper to stop between push slices; an in-flight sherpa decode still finishes. */
    fun cancel() {
        val active = synchronized(this) { handle }
        if (active != 0L) DesktopQwenNative.cancel(active)
    }

    override fun close() {
        val retired = synchronized(this) { val value = handle; handle = 0; value }
        if (retired != 0L) DesktopQwenNative.destroy(retired)
    }

    companion object {
        fun open(directory: Path, language: String, threads: Int): DesktopQwenSpeechEngine {
            require(language == "auto" || language in QwenSpeechModel.languages) { "Unsupported Qwen3-ASR source language: $language" }
            require(threads in 1..32) { "Qwen3-ASR threads must be 1–32" }
            check(DesktopQwenNative.protocolVersion() == QwenSpeechModel.PROTOCOL_VERSION) { "Desktop Qwen3-ASR native protocol mismatch; rebuild the native bundle" }
            val handle = DesktopQwenNative.create(directory.toString().toByteArray(), language.toByteArray(), threads)
            check(handle != 0L) { "Qwen3-ASR returned an invalid model handle" }
            val execution = try {
                DesktopQwenNative.executionInfo(handle).toString(Charsets.UTF_8).ifBlank { "CPU · ONNX Runtime" }
            } catch (error: Throwable) {
                DesktopQwenNative.destroy(handle)
                throw error
            }
            return DesktopQwenSpeechEngine(handle, execution)
        }
    }
}
