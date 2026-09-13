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
}
