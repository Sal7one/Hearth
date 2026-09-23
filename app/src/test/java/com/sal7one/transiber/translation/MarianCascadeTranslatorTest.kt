package com.sal7one.transiber.translation

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarianCascadeTranslatorTest {
    @Test fun advertisedDirectionsRequireBothPinnedPairs() {
        assertEquals(setOf("ru", "zh"), MarianCascade.routes.map { it.source }.toSet())
        MarianCascade.routes.forEach { route ->
            assertEquals("en", route.first.target)
            assertEquals("en", route.second.source)
            assertTrue(TranslationOptions.supports(route.id, route.source, "ar"))
            assertFalse(TranslationOptions.supports(route.id, "en", "ar"))
        }
    }

    private class Fake(
        override val id: String,
        direction: TranslationDirection,
        private val response: String,
    ) : CancellableTextTranslator {
        override val directions = setOf(direction)
        var input: String? = null
        var cancelled = false
        var closed = false
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            require(direction in directions)
            input = text
            return response
        }
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }

    @Test fun routesThroughEnglishAndOwnsBothModels() = runBlocking {
        val first = Fake("ru-en", TranslationDirection("ru", "en"), "two train tickets")
        val second = Fake("en-ar", TranslationDirection("en", "ar"), "تذكرتان للقطار")
        val cascade = MarianCascadeTranslator("ru-ar", first, second, TranslationDirection("ru", "ar"))
        assertEquals("تذكرتان للقطار", cascade.translate("два билета на поезд", TranslationDirection("ru", "ar")))
        assertEquals("два билета на поезд", first.input)
        assertEquals("two train tickets", second.input)
        cascade.cancel(); cascade.close()
        assertTrue(first.cancelled && second.cancelled)
        assertTrue(first.closed && second.closed)
    }

    @Test fun emptyIntermediateDoesNotInventSuccess() = runBlocking {
        val first = Fake("zh-en", TranslationDirection("zh", "en"), "")
        val second = Fake("en-ar", TranslationDirection("en", "ar"), "some text")
        val cascade = MarianCascadeTranslator("zh-ar", first, second, TranslationDirection("zh", "ar"))
        try { cascade.translate("中文", TranslationDirection("zh", "ar")); fail("Expected empty intermediate failure") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("empty English")) }
        assertNull(second.input)
        cascade.close()
    }
}
