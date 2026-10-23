package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopBackendSpeechNative
import java.nio.file.Path

/**
 * One pinned speech backend dylib (TranscribePsy, Nemotron, ...) through the shared
 * HearthSpeechBackend ABI. The backend library is loaded and hash-verified by
 * DesktopNativeLibraries before this engine asks JNI to dlopen it.
 */
internal class DesktopBackendSpeechEngine private constructor(
    private var handle: Long,
    val executionInfo: String,
) : AutoCloseable {
    fun transcribe(pcm: FloatArray): String {
        require(pcm.isNotEmpty()) { "Speech requires non-empty 16 kHz audio" }
        val active = synchronized(this) { handle }
        check(active != 0L) { "Speech backend session is closed" }
        return DesktopBackendSpeechNative.transcribe(active, pcm).toString(Charsets.UTF_8).trim()
    }

    fun language(): String {
        val active = synchronized(this) { handle }
        if (active == 0L) return ""
        return DesktopBackendSpeechNative.language(active).toString(Charsets.UTF_8)
    }

    fun cancel() {
        val active = synchronized(this) { handle }
        if (active != 0L) DesktopBackendSpeechNative.cancel(active)
    }

    override fun close() {
        val retired = synchronized(this) { val value = handle; handle = 0; value }
        if (retired != 0L) DesktopBackendSpeechNative.destroy(retired)
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        fun open(
            backendLibrary: String,
            backendId: String,
            model: Path,
            language: String,
            threads: Int,
            entrySymbol: String = "hearth_speech_backend_v1",
            encoder: Path? = null,
            decoder: Path? = null,
            tokenizer: Path? = null,
            convFrontend: Path? = null,
            rightContext: Int = 3,
            maximumMs: Int = 15000,
            silenceMs: Int = 600,
            thresholdDb: Float = -45f,
        ): DesktopBackendSpeechEngine {
            require(threads in 1..32) { "Speech threads must be 1–32" }
            check(DesktopBackendSpeechNative.protocolVersion() == PROTOCOL_VERSION) { "Desktop speech backend protocol mismatch; rebuild the native bundle" }
            val library = DesktopNativeLibraries.libraryPath(backendLibrary)
            val handle = DesktopBackendSpeechNative.create(
                library.toString().toByteArray(), backendId.toByteArray(), entrySymbol.toByteArray(), model.toString().toByteArray(),
                encoder?.toString().orEmpty().toByteArray(), decoder?.toString().orEmpty().toByteArray(),
                tokenizer?.toString().orEmpty().toByteArray(), convFrontend?.toString().orEmpty().toByteArray(),
                language.toByteArray(),
                threads, rightContext, maximumMs, silenceMs, thresholdDb,
            )
            check(handle != 0L) { "Speech backend returned an invalid handle" }
            val execution = try {
                DesktopBackendSpeechNative.executionInfo(handle).toString(Charsets.UTF_8).ifBlank { "CPU" }
            } catch (error: Throwable) {
                DesktopBackendSpeechNative.destroy(handle)
                throw error
            }
            return DesktopBackendSpeechEngine(handle, execution)
        }
    }
}
