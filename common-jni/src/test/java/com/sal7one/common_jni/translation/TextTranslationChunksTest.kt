package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TextTranslationChunksTest {
    private val direction = TranslationDirection("ar", "en")
    private class Translator : CancellableTextTranslator {
        override val id = "test"
        override val directions = setOf(TranslationDirection("ar", "en"))
        val inputs = mutableListOf<String>()
        var closed = 0
        var failAt = -1
        var blankAt = -1
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            inputs += text
            if (inputs.size == failAt) error("actual native failure")
            return if (inputs.size == blankAt) " " else "part${inputs.size}"
        }
        override fun cancel() = Unit
        override fun close() { closed++ }
    }
    @Test fun multilingualTextIsPreservedAndEveryRequestIsBounded() {
        val text = "السلام عليكم. Hello 3.5! Привет мир؟ 你好世界。 😀 ".repeat(60).trim()
        val chunks = TextTranslationChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 320 && it.isNotEmpty() })
        assertTrue(chunks.size > 1)
    }
    @Test fun longWordsAndSupplementaryUnicodeCannotSplitASurrogatePair() {
        for (limit in 2..25) {
            val text = "😀".repeat(50) + "x".repeat(80)
            val chunks = TextTranslationChunks.split(text, limit)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= limit && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        }
    }
    @Test fun prefersSentenceBoundaryAndDoesNotTreatDecimalAsSentenceEnd() {
        val chunks = TextTranslationChunks.split("First sentence. Next sentence with 3.5 in it.", 32)
        assertEquals("First sentence.", chunks.first())
        assertTrue(chunks.any { it.contains("3.5") })
    }
    @Test fun rejectsBlankOversizedInputAndInvalidChunkSizes() {
        for (text in listOf(" ", "x".repeat(32001))) assertThrows(IllegalArgumentException::class.java) { TextTranslationChunks.split(text) }
        for (limit in listOf(0, 1, 5001)) assertThrows(IllegalArgumentException::class.java) { TextTranslationChunks.split("hello", limit) }
    }
    @Test fun translatesInOrderWithProgressAndLeavesOwnershipWithCaller() = runBlocking {
        val translator = Translator()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = TextTranslationChunks.translate(translator, "مرحبا ".repeat(120), direction, 320) { done, total -> progress += done to total }
        assertEquals(translator.inputs.indices.joinToString(" ") { "part${it + 1}" }, result)
        assertTrue(translator.inputs.all { it.length <= 320 })
        assertEquals(0 to translator.inputs.size, progress.first())
        assertEquals(translator.inputs.size to translator.inputs.size, progress.last())
        assertEquals(0, translator.closed)
    }
    @Test fun failureAndEmptyResultsNeverPublishAnIncompleteSuccess() = runBlocking {
        for (blank in listOf(false, true)) {
            val translator = Translator().apply { if (blank) blankAt = 2 else failAt = 2 }
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { TextTranslationChunks.translate(translator, "مرحبا ".repeat(120), direction) }
            }
            assertEquals(2, translator.inputs.size)
            assertTrue(failure.message!!.startsWith("Translation part 2/"))
            assertTrue(failure.message!!.contains(if (blank) "empty translation" else "actual native failure"))
        }
    }
    @Test fun cancellationDoesNotStartAnotherChunkOrReclassifyAnAbortAsFailure() = runBlocking {
        var calls = 0
        val task = launch {
            val translator = object : CancellableTextTranslator {
                override val id = "abort"
                override val directions = setOf(direction)
                override fun cancel() = Unit
                override fun close() = Unit
                override suspend fun translate(text: String, direction: TranslationDirection): String {
                    calls++
                    currentCoroutineContext().cancel()
                    error("Local translation cancelled\ngraph_compute failed with error 1")
                }
            }
            TextTranslationChunks.translate(translator, "مرحبا ".repeat(120), direction)
            fail("Cancelled translation returned success")
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, calls)
    }
}
