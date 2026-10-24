package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.translation.Ct2Native
import com.sal7one.hearth.core.Ct2TextTranslation
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

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
    private val cancelled = AtomicBoolean(false)
    val executionInfo: String =
        "CPU · CTranslate2 4.8.3 · Ruy · ${if (mode in 1..3) "INT8_FLOAT32" else "FLOAT32"} · 2 threads · greedy"

    fun translate(text: String, targetLanguage: String): String {
        val active = synchronized(this) { handle }
        check(active != 0L) { "CTranslate2 session is closed" }
        fun checkActive() {
            check(!cancelled.get() && synchronized(this) { handle == active }) { "CT2 translation cancelled or closed" }
        }
        val target = targetLanguage.toByteArray(Charsets.UTF_8)
        return Ct2TextTranslation.translate(text, ::checkActive) { part ->
            Ct2Native.translate(active, part.toByteArray(Charsets.UTF_8), target).toString(Charsets.UTF_8)
        }
    }

    fun cancel() {
        cancelled.set(true)
        val active = synchronized(this) { handle }
        if (active != 0L) Ct2Native.cancel(active)
    }

    override fun close() {
        cancelled.set(true)
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
