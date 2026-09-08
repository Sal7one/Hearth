package com.sal7one.common_jni.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechTranslationTest {
    private class Translator(override val id: String, override val directions: Set<TranslationDirection>) : SpeechTextTranslator {
        val calls = mutableListOf<Pair<String, TranslationDirection>>()
        var error: Exception? = null
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            error?.let { throw it }
            calls.add(text to direction)
            return "$text:${direction.target}"
        }
    }
    private fun final(source: String?) = SpeechTranscript(1, 1, "source", source, true, 16000)
    @Test fun russianChineseNeverEnterEnglishOnlyMarian() = runBlocking {
        val marian = Translator("Marian", setOf(TranslationDirection("en", "ar")))
        val captions = SpeechCaptionTranslator(listOf(marian))
        for (source in listOf("ru", "zh")) {
            assertFalse(captions.canTranslate(source, "ar"))
            try { captions.caption(final(source), "ar"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("$source -> ar")) }
        }
        assertTrue(marian.calls.isEmpty())
    }
    @Test fun validEnglishPivotUsesTwoExplicitDirections() = runBlocking {
        val engine = Translator("multi", setOf(TranslationDirection("ru", "en"), TranslationDirection("en", "ar")))
        val result = SpeechCaptionTranslator(listOf(engine)).caption(final("ru"), "ar")
        assertEquals("source:en:ar", result.translatedText)
        assertEquals("source:en", engine.calls[1].first)
    }
    @Test fun directTranslationWinsOverPivot() = runBlocking {
        val pivot = Translator("pivot", setOf(TranslationDirection("ru", "en"), TranslationDirection("en", "ar")))
        val direct = Translator("direct", setOf(TranslationDirection("ru", "ar")))
        SpeechCaptionTranslator(listOf(pivot, direct)).caption(final("ru"), "ar")
        assertEquals(1, direct.calls.size); assertTrue(pivot.calls.isEmpty())
    }
    @Test fun sameLanguageCCRequiresNoTranslator() = runBlocking {
        val p = SpeechCaptionTranslator(emptyList())
        assertNull(p.caption(final("ru"), "ru").translatedText)
        assertNull(p.caption(final(null)).translatedText)
    }
    @Test fun failedTranslationPreservesExactCause() = runBlocking {
        val engine = Translator("test", setOf(TranslationDirection("zh", "ar")))
        val error = IllegalStateException("HTTP 429: 原文 🧪"); engine.error = error
        try { SpeechCaptionTranslator(listOf(engine)).caption(final("zh"), "ar"); fail() }
        catch (e: Exception) { assertSame(error, e) }
    }
    @Test fun unknownSourceAndPartialAreNotTranslated() = runBlocking {
        val p = SpeechCaptionTranslator(emptyList())
        try { p.caption(final(null), "ar"); fail() } catch (_: IllegalStateException) {}
        try { p.caption(final("ru").copy(isFinal = false), "en"); fail() } catch (_: IllegalArgumentException) {}
    }
}
