package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.translation.Ct2Native
import java.nio.file.Path

/**
 * Desktop CTranslate2 (Marian/OPUS/QuickMT) translator.
 *
 * The native library statically embeds CTranslate2, SentencePiece and the Rust byte-BPE
 * tokenizer. One handle owns one model directory; closing retires it and waits for an
 * in-flight translation.
 */
internal class DesktopCt2TranslationEngine private constructor(
    private var handle: Long,
    val mode: Int,
) : AutoCloseable {
    val executionInfo: String =
        "CPU · CTranslate2 4.8.3 · Ruy · ${if (mode in 1..3) "INT8_FLOAT32" else "FLOAT32"} · 2 threads · greedy"

    fun translate(text: String, targetLanguage: String): String {
        require(text.isNotBlank() && text.length <= 2000) { "CTranslate2 accepts 1–2000 caption characters" }
        val active = synchronized(this) { handle }
        check(active != 0L) { "CTranslate2 session is closed" }
        val output = Ct2Native.translate(active, text.toByteArray(Charsets.UTF_8), targetLanguage.toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8).trim()
        check(output.isNotBlank()) { "CTranslate2 returned empty text" }
        return output
    }

    fun cancel() {
        val active = synchronized(this) { handle }
        if (active != 0L) Ct2Native.cancel(active)
    }

    override fun close() {
        val retired = synchronized(this) { val value = handle; handle = 0; value }
        if (retired != 0L) Ct2Native.destroy(retired)
    }

    companion object {
        const val PROTOCOL_VERSION = 2

        fun open(directory: Path, mode: Int): DesktopCt2TranslationEngine {
            require(mode in 0..5) { "Unsupported CTranslate2 tokenizer mode: $mode" }
            check(Ct2Native.protocolVersion() == PROTOCOL_VERSION) { "Desktop CTranslate2 protocol mismatch; rebuild the native bundle" }
            val handle = Ct2Native.create(directory.toString().toByteArray(), mode)
            check(handle != 0L) { "CTranslate2 returned an invalid model handle" }
            return DesktopCt2TranslationEngine(handle, mode)
        }
    }
}
