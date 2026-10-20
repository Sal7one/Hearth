package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded text requests; preserves input order, Unicode and the caller's translator ownership. */
object TextTranslationChunks {
    const val MAX_TEXT_CHARACTERS = 32000

    fun split(text: String, maximumCharacters: Int = 320): List<String> =
        com.sal7one.hearth.core.TextChunks.split(text, maximumCharacters)

    /** All-or-error: never return incomplete translation as success or automatically retry a provider. */
    suspend fun translate(
        translator: CancellableTextTranslator,
        text: String,
        direction: TranslationDirection,
        maximumCharacters: Int = 320,
        progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): String {
        val chunks = split(text, maximumCharacters).filter(String::isNotBlank)
        val outputs = ArrayList<String>(chunks.size)
        currentCoroutineContext().ensureActive()
        progress(0, chunks.size)
        for ((index, chunk) in chunks.withIndex()) {
            currentCoroutineContext().ensureActive()
            val translated = try {
                translator.translate(chunk, direction).trim().also {
                    check(it.isNotBlank()) { "${translator.id} returned empty translation" }
                }
            } catch (error: Exception) {
                // JNI may deliver an abort exception while its coroutine is already cancelled.
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("Translation part ${index + 1}/${chunks.size}: ${error.message ?: error}", error)
            }
            currentCoroutineContext().ensureActive()
            outputs += translated
            progress(index + 1, chunks.size)
        }
        return outputs.joinToString(" ")
    }
}
