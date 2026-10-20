package com.sal7one.common_jni.voice
import org.junit.Assert.*
import org.junit.Test
class VoiceTextTest {
    @Test fun languageTagsAndArabicSurviveNormalization() {
        assertEquals("<ar>مرحبا بالعالم.</ar>",VoiceText.prepare("مرحبا بالعالم","ar"))
        assertEquals("<fr>cafe\u0301.</fr>",VoiceText.prepare("café","fr"))
        assertEquals(31,VoiceText.supertonicLanguages.size)
        assertTrue("ar" in VoiceText.supertonicLanguages)
        assertFalse("zh" in VoiceText.supertonicLanguages)
    }
    @Test fun splitsOnClausesWithoutDroppingWordsOrSplittingSurrogates() {
        val words=(1..100).joinToString(" "){"word$it"}
        assertEquals(words,VoiceText.chunks(words,40).joinToString(" "))
        val text="x".repeat(15)+"😀"+"y".repeat(30)
        val chunks=VoiceText.chunks(text,16)
        assertEquals(text,chunks.joinToString(""))
        assertTrue(chunks.all {it.length<=16 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first())})
    }
    @Test fun unsupportedAndUnencodableInputsFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java){VoiceText.prepare("你好","zh")}
        assertThrows(IllegalArgumentException::class.java){VoiceText.prepare("😀","en")}
        assertThrows(IllegalArgumentException::class.java){VoiceText.chunks("x".repeat(5001))}
        assertThrows(IllegalArgumentException::class.java){VoiceText.ids("hello","en",LongArray(10))}
        val indexer=LongArray(256){it.toLong()}
        assertArrayEquals("<en>Hello.</en>".map {it.code.toLong()}.toLongArray(),VoiceText.ids("Hello","en",indexer))
    }
    @Test fun decimalAbbreviationAndWebsiteAreNotSentenceEndings() {
        for ((text, token) in listOf(
            "The total amount is 12.50 dollars and delivery is included." to "12.50",
            "Please speak with Dr. Smith about the delivery tomorrow." to "Dr. Smith",
            "Read the guide at https://example.com/help before starting today." to "https://example.com/help",
            "Please contact the U.S. office about your reservation tomorrow." to "U.S. office",
        )) {
            val chunks = VoiceText.chunks(text, 40)
            assertTrue("$token was split: $chunks", chunks.any { token in it })
            assertEquals(text, chunks.joinToString(" "))
        }
    }
    @Test fun prefersArabicAndCjkClausesOverArbitraryWordCuts() {
        val arabic = "مرحبا بكم في هذا اللقاء، سنشرح طريقة استخدام التطبيق اليوم"
        assertEquals("مرحبا بكم في هذا اللقاء،", VoiceText.chunks(arabic, 32).first())
        val japanese = "これは最初の説明です。これは次の説明です。最後の説明も続きます。"
        val chunks = VoiceText.chunks(japanese, 24)
        assertTrue(chunks.first().endsWith("。"))
        assertEquals(japanese, chunks.joinToString(""))
    }
    @Test fun keepsClosingQuotesAndPunctuationWithSpeechWithinTheLimit() {
        for (text in listOf(
            "a".repeat(39) + ".\"",
            "a".repeat(39) + ".\" Another sentence follows.",
            "One short phrase ends here.\" Another phrase is longer than the limit.",
            "a".repeat(39) + " ...",
            "مرحبا ".repeat(7).trimEnd() + "؟»",
        )) {
            val chunks = VoiceText.chunks(text, 40)
            assertTrue(chunks.toString(), chunks.all { it.length <= 40 && it.any(Char::isLetterOrDigit) })
            assertEquals(text.filterNot(Char::isWhitespace), chunks.joinToString("").filterNot(Char::isWhitespace))
            assertFalse(chunks.toString(), chunks.any { it.startsWith('»') || it.startsWith('"') })
        }
    }
    @Test fun mixedUnicodePreservesAllContentAndEveryEngineLimit() {
        val text = ("مرحبا؟ سعره 12.50، Dr. Smith says: «Hello!» 日本語です。😀 " + "x".repeat(320)).repeat(8)
        for (limit in listOf(16, 17, 40, 180, 300)) {
            val chunks = VoiceText.chunks(text, limit)
            assertTrue(chunks.all {it.isNotBlank() && it.length <= limit})
            assertTrue(chunks.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
            assertEquals(text.filterNot(Char::isWhitespace), chunks.joinToString("").filterNot(Char::isWhitespace))
        }
    }
}
