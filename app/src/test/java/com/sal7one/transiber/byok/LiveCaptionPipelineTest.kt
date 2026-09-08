package com.sal7one.transiber.byok

import org.junit.Assert.*
import org.junit.Test

class LiveCaptionPipelineTest {
    @Test fun translationPreservesWhitespaceTokensAndArabic() {
        val t = TranslationTranscript()
        t.append("مرحبا"); t.append(" "); t.append("بالعالم")
        assertEquals("مرحبا بالعالم", t.partial)
        assertEquals(listOf("مرحبا بالعالم!"), t.append("!"))
        assertEquals("", t.partial)
    }
    @Test fun continuousChineseIsBoundedWithoutSplittingEmoji() {
        val t = TranslationTranscript(12)
        val input = "你好世界你好世界你好世😀界你好世界你好世界"
        val finals = t.append(input)
        val output = finals.joinToString("") + t.flush()
        assertEquals(input, output)
        finals.forEach { assertFalse(Character.isHighSurrogate(it.last())) }
        assertTrue(finals.all { it.length <= 12 })
    }
    @Test fun translationFlushesTailOnce() {
        val t = TranslationTranscript()
        t.append("a translated phrase")
        assertEquals("a translated phrase", t.flush())
        assertEquals("", t.flush())
    }
    @Test fun sourceItemsDoNotMixWhenFinalsArriveInReverseOrder() {
        val t = RealtimeTranscript()
        t.begin("a"); t.append("a", "Первый")
        t.begin("b"); t.append("b", "第二")
        t.append("a", " текст")
        assertEquals("第二", t.partial)
        assertTrue(t.finish("b", "第二句话").isEmpty())
        assertEquals(listOf("Первый текст", "第二句话"), t.finish("a", "Первый текст"))
        assertTrue(t.finish("a", "Первый текст").isEmpty())
    }
    @Test fun identicalSentencesFromDifferentItemsAreBothKept() {
        val t = RealtimeTranscript()
        assertEquals(listOf("yes"), t.finish("a", "yes"))
        assertEquals(listOf("yes"), t.finish("b", "yes"))
    }
    @Test fun streamingCcDoesNotGenerateAssistantResponsesAndUsesSourceHint() {
        val input = OpenAiRealtimeClient.sessionUpdate("gpt-live-transcribe", "ru")
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertFalse(input.getJSONObject("turn_detection").getBoolean("create_response"))
        assertFalse(input.getJSONObject("turn_detection").getBoolean("interrupt_response"))
        assertEquals("ru", input.getJSONObject("transcription").getJSONArray("languages").getString(0))
    }
    @Test fun silenceCompletesShortBatchBeforeHardCap() {
        val b = UtteranceBuffer(16000, maxMs = 4000)
        repeat(20) { assertNull(b.push(ShortArray(800) { 1000 }, 16000, true)) }
        repeat(12) { assertNull(b.push(ShortArray(800), 16000, false)) }
        assertEquals(26400, b.push(ShortArray(800), 16000, false)!!.size)
        assertNull(b.flush())
    }
    @Test fun continuousSpeechHasFourSecondLatencyBudget() {
        val b = UtteranceBuffer(16000, maxMs = 4000)
        repeat(79) { assertNull(b.push(ShortArray(800) { 1000 }, 16000, true)) }
        assertEquals(64000, b.push(ShortArray(800) { 1000 }, 16000, true)!!.size)
    }
}
