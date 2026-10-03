package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded text requests; preserves input order, Unicode and the caller's translator ownership. */
object TextTranslationChunks {
    const val MAX_TEXT_CHARACTERS = 32000

    fun split(text: String, maximumCharacters: Int = 320): List<String> {
        require(maximumCharacters in 2..5000) { "Translation chunk size must be 2–5000 characters" }
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARACTERS) {
            "Translation accepts 1–32000 characters per turn"
        }
        val input = text.trim()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < input.length) {
            var end = minOf(start + maximumCharacters, input.length)
            if (end < input.length && input[end - 1].isHighSurrogate() && input[end].isLowSurrogate()) end--
            if (end < input.length) {
                // Prefer complete sentences, then words. A decimal point is not a sentence boundary.
                val minimum = start + (end - start) / 2
                var word = -1
                var sentence = -1
                for (i in start until end) {
                    if (i >= minimum && input[i].isWhitespace()) word = i + 1
                    if (input[i] in "\n。！？؟" ||
                        (input[i] in ".!?" && input.getOrNull(i + 1)?.isWhitespace() == true)) sentence = i + 1
                }
                end = when { sentence > start -> sentence; word > start -> word; else -> end }
            }
            chunks += input.substring(start, end)
            start = end
        }
        return chunks
    }

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
