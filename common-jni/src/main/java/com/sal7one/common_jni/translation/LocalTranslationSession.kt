package com.sal7one.common_jni.translation

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechTextTranslator
import com.sal7one.common_jni.speech.TranslationDirection
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object LocalTranslationNative {
    init { System.loadLibrary("transiber_translation") }
    external fun promptProtocolVersion(): Int
    external fun create(path: ByteArray, rawPrompt: Boolean): Long
    external fun translate(handle: Long, prompt: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
interface CancellableTextTranslator : SpeechTextTranslator, AutoCloseable { fun cancel() }
/** One model/context per bridge. Calls are serialized natively; close cancels before retiring the handle. */
class LocalTranslationSession private constructor(private val spec: TranslationModelSpec, handle: Long) : CancellableTextTranslator {
    private val handle = AtomicLong(handle)
    override val id get() = spec.id
    override val directions get() = spec.directions
    override suspend fun translate(text: String, direction: TranslationDirection): String = withContext(Dispatchers.IO) {
        val current = handle.get(); check(current != 0L) { "Local translation session is closed" }
        val prompt = spec.prompt(text, direction.source, direction.target)
        LocalTranslationNative.translate(current, prompt.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8).trim()
            .also { check(it.isNotBlank()) { "Local translation returned empty text" } }
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let(LocalTranslationNative::cancel) }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(LocalTranslationNative::destroy) }
    companion object {
        /** Blocking ownership-safe load; caller must keep this off Main and close even after cancellation. */
        fun open(file: File, spec: TranslationModelSpec): LocalTranslationSession {
            val protocol = try { LocalTranslationNative.promptProtocolVersion() }
                catch (e: UnsatisfiedLinkError) { throw IllegalStateException("Local translation native runtime is outdated: ${e.message}", e) }
            check(protocol == 2) { "Local translation native prompt protocol mismatch: expected 2, got $protocol" }
            check(file.length() == spec.bytes) { "Local translation model is missing or has the wrong size" }
            ModelIntegrity.inspect(file, spec.sha256)
            val handle = LocalTranslationNative.create(file.absolutePath.toByteArray(Charsets.UTF_8), spec.family == "milmmt-46")
            check(handle != 0L) { "Local translation returned an invalid native handle" }
            return LocalTranslationSession(spec, handle)
        }
    }
}
