package com.sal7one.transiber.translation

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarianCascadeTranslatorTest {
    @Test fun advertisedDirectionsRequireBothPinnedPairs() {
        // These are product-supported directions, independent of how routes are generated.
        val expected = setOf(
            TranslationDirection("ar", "ru"), TranslationDirection("ar", "zh"), TranslationDirection("ar", "es"),
            TranslationDirection("ru", "ar"), TranslationDirection("ru", "zh"), TranslationDirection("ru", "es"),
            TranslationDirection("zh", "ar"), TranslationDirection("zh", "ru"), TranslationDirection("zh", "es"),
            TranslationDirection("es", "ar"), TranslationDirection("es", "ru"), TranslationDirection("es", "zh"),
        )
        assertEquals(expected, MarianCascade.routes.map { TranslationDirection(it.source, it.target) }.toSet())
        assertEquals(expected.size, MarianCascade.routes.size)
        assertEquals(expected.size, MarianCascade.routes.map { it.id }.toSet().size)
        MarianCascade.routes.forEach { route ->
            assertSame(MarianPackage.find(route.first.id), route.first)
            assertSame(MarianPackage.find(route.second.id), route.second)
            assertEquals(route.source, route.first.source)
            assertEquals("en", route.first.target)
            assertEquals("en", route.second.source)
            assertEquals(route.target, route.second.target)
            assertTrue(TranslationOptions.supports(route.id, route.source, route.target))
            assertFalse(TranslationOptions.supports(route.id, route.target, route.source))
            assertFalse(TranslationOptions.supports(route.id, "en", route.target))
            assertFalse(TranslationOptions.supports(route.id, route.source, "en"))
            assertFalse(TranslationOptions.supports(route.id, route.source, route.source))
        }
    }

    @Test fun eitherMissingDirectionFailsBeforeStartingInference() = runBlocking {
        val direction = TranslationDirection("ru", "es")
        for (missingFirst in listOf(true, false)) {
            val first = Fake("ru-en",
                if (missingFirst) TranslationDirection("zh", "en") else TranslationDirection("ru", "en"),
                "two tickets")
            val second = Fake("en-es",
                if (missingFirst) TranslationDirection("en", "es") else TranslationDirection("en", "ar"),
                "dos billetes")
            val cascade = MarianCascadeTranslator("ru-es", first, second, direction)
            try {
                cascade.translate("два билета", direction)
                fail("Expected missing ${if (missingFirst) "first" else "second"} direction failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("missing one of its two installed language directions"))
            } finally {
                cascade.close()
            }
            assertNull(first.input)
            assertNull(second.input)
            assertTrue(first.closed && second.closed)
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
