package com.sal7one.transiber.conversation

import com.sal7one.transiber.caption.CaptionLine
import org.junit.Assert.*
import org.junit.Test

class ConversationTranscriptTest {
    @Test fun displayTailEvictionDoesNotLoseEarlierSpeechAndCorrectionsKeepOneIdentity() {
        val transcript = ConversationTranscript()
        val lines = (1L..180L).map { CaptionLine(id = it, original = "word$it") }
        for (end in lines.indices) transcript.observe(lines.take(end + 1).takeLast(120))
        assertEquals(lines.joinToString(" ") { it.original }, transcript.text)
        transcript.observe(listOf(lines.last().copy(original = "corrected")))
        assertTrue(transcript.text.startsWith("word1 "))
        assertTrue(transcript.text.endsWith("word179 corrected"))
        assertEquals(180, transcript.text.split(' ').size)
    }
    @Test fun unchangedSnapshotsAndEmptyCaptionsDoNotDuplicateText() {
        val transcript = ConversationTranscript()
        val history = listOf(CaptionLine(id = 1, original = "السلام عليكم"), CaptionLine(id = 2, original = " "))
        repeat(100) { assertEquals("السلام عليكم", transcript.observe(history)) }
    }
    @Test fun storageLimitFailsVisiblyAndKeepsAlreadyAcceptedOriginal() {
        val transcript = ConversationTranscript()
        transcript.observe(listOf(CaptionLine(id = 1, original = "x".repeat(32000))))
        val failure = assertThrows(IllegalStateException::class.java) {
            transcript.observe(listOf(CaptionLine(id = 2, original = "overflow")))
        }
        assertTrue(failure.message!!.contains("32000"))
        assertEquals("x".repeat(32000), transcript.text)
    }
    @Test fun oneTurnCannotContaminateTheNextTurn() {
        val first = ConversationTranscript()
        first.observe(listOf(CaptionLine(id = 1, original = "old")))
        val next = ConversationTranscript()
        assertEquals("new", next.observe(listOf(CaptionLine(id = 1, original = "new"))))
        assertEquals("old", first.text)
    }
    @Test fun newAndGrowingCardsFollowTheBottomWithoutPullingAReaderBackDown() {
        assertEquals(ConversationScroll.ShowLast(4), conversationScroll(true, false, 5, 2, 600, 600))
        assertEquals(ConversationScroll.RevealBottom(900), conversationScroll(true, false, 5, 4, 1500, 600))
        assertEquals(ConversationScroll.Stay, conversationScroll(true, false, 5, 4, 600, 600))
        assertEquals(ConversationScroll.Stay, conversationScroll(false, false, 5, 2, 600, 600))
        assertEquals(ConversationScroll.Stay, conversationScroll(true, true, 5, 4, 1500, 600))
        assertEquals(ConversationScroll.Stay, conversationScroll(true, false, 0, null, 0, 600))
    }
    @Test fun appScrollingNeverDisablesFollowingButUserScrollingDoes() {
        assertTrue(conversationFollowAfterScroll(true, true, true, true))
        assertFalse(conversationFollowAfterScroll(true, true, true, false))
        assertFalse(conversationFollowAfterScroll(false, false, true, false))
        assertTrue(conversationFollowAfterScroll(false, false, false, false))
    }
}
