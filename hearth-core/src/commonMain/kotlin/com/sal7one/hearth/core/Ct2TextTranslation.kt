package com.sal7one.hearth.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A complete source region could not fit within the model's bounded decoding work. */
class TranslationContentLimitException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Keeps article-sized CT2 requests inside the existing caption-sized native decoder limits. */
object Ct2TextTranslation {
    const val MAX_REQUEST_CHARACTERS = 3000
    private const val INITIAL_CHUNK_CHARACTERS = 320
    private const val MINIMUM_RETRY_CHARACTERS = 64
    private const val MAX_NATIVE_CALLS = 64
    private const val OUTPUT_LIMIT =
        "CT2 translation exceeded its output token limit; incomplete text was not published"
    private const val INPUT_LIMIT = "CT2 input exceeds 1022 tokens; split the text first"

    fun translate(
        text: String,
        ensureActive: () -> Unit = {},
        translatePart: (String) -> String,
    ): String {
        val started = TimeSource.Monotonic.markNow()
        return translateWithinBudget(text, ensureActive, { started.elapsedNow() }, translatePart)
    }

    internal fun translateWithinBudget(
        text: String,
        ensureActive: () -> Unit = {},
        elapsed: () -> Duration,
        translatePart: (String) -> String,
    ): String {
        require(text.isNotBlank()) { "CT2 translation requires nonempty text" }
        if (text.length > MAX_REQUEST_CHARACTERS) {
            throw TranslationContentLimitException(
                "CT2 translation accepts at most $MAX_REQUEST_CHARACTERS characters per request; choose a smaller text region",
            )
        }
        var calls = 0
        var lastLimit: RuntimeException? = null

        fun checkActiveAndBudget() {
            ensureActive()
            // This check cannot interrupt a blocking native call. Its existing token callback
            // still enforces the native deadline; this also bounds retries between calls.
            check(elapsed() < 20.seconds) {
                "CT2 translation request exceeded 20 seconds; incomplete text was not published"
            }
        }

        fun contentLimit(cause: RuntimeException?): Nothing = throw TranslationContentLimitException(
            "CT2 could not translate this text within its content limits; choose a smaller text region",
            cause,
        )

        fun translateChunk(chunk: String): String {
            checkActiveAndBudget()
            // TextChunks preserves internal whitespace. A gap between paragraphs needs no
            // decoder call, but is retained when assembling the complete region.
            if (chunk.isBlank()) return chunk
            if (calls >= MAX_NATIVE_CALLS) contentLimit(lastLimit)
            calls++
            val output = try {
                translatePart(chunk)
            } catch (error: RuntimeException) {
                if (error.message != OUTPUT_LIMIT && error.message != INPUT_LIMIT) throw error
                lastLimit = error
                checkActiveAndBudget()
                if (chunk.length <= MINIMUM_RETRY_CHARACTERS) contentLimit(error)
                val smaller = splitPreservingText(chunk, maxOf(2, chunk.length / 2))
                if (smaller.size < 2) contentLimit(error)
                return smaller.joinToString(" ") { translateChunk(it) }
            }
            checkActiveAndBudget()
            check(output.isNotBlank()) { "CT2 returned empty text" }
            return output.trim()
        }

        // Keep successes local: a later failure must never publish a partial region.
        return TextChunks.split(text, INITIAL_CHUNK_CHARACTERS)
            .joinToString(" ") { translateChunk(it) }
            .trim()
    }

    private fun splitPreservingText(text: String, maximumCharacters: Int): List<String> {
        val chunks = TextChunks.split(text, maximumCharacters).toMutableList()
        // The public splitter trims the whole request. Recursive parts are internal
        // boundaries, so retain their spaces instead of trimming the source again.
        val leading = text.takeWhile(Char::isWhitespace)
        val trailing = text.takeLastWhile(Char::isWhitespace)
        chunks[0] = leading + chunks[0]
        chunks[chunks.lastIndex] = chunks.last() + trailing
        return chunks
    }
}
