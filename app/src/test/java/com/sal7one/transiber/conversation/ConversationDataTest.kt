package com.sal7one.transiber.conversation

import org.junit.Assert.*
import org.junit.Test

class ConversationDataTest {
    @Test fun lateTranslationCannotChangeAnotherSessionOrDeletedTurn() {
        val turn = ConversationTurn(id = "turn", speaker = 0, source = "ru", target = "ar", original = "Привет")
        val session = ConversationSession(id = "session", turns = listOf(turn))
        val result = turn.copy(translation = "مرحبا", status = TurnStatus.COMPLETE)
        assertEquals(session, session.updateTurn("another", result))
        assertEquals(emptyList<ConversationTurn>(), session.copy(turns = emptyList()).updateTurn("session", result).turns)
        assertEquals("Привет", session.updateTurn("session", result).turns.single().original)
        assertEquals("مرحبا", session.updateTurn("session", result).turns.single().translation)
    }
    @Test fun restartPreservesCompletedTextAndMarksPendingWorkInsteadOfRetrying() {
        val turn = ConversationTurn(speaker = 1, source = "en", target = "ar", original = "Train station", status = TurnStatus.TRANSLATING)
        val complete = turn.copy(id = "complete", translation = "محطة القطار", status = TurnStatus.COMPLETE)
        val restored = ConversationSession(turns = listOf(turn, complete)).interrupted()
        assertEquals(TurnStatus.INTERRUPTED, restored.turns.first().status)
        assertEquals("Train station", restored.turns.first().original)
        assertEquals(complete, restored.turns.last())
    }
    @Test fun languageSwapsDoNotRewritePastTurnDirections() {
        val turn = ConversationTurn(speaker = 0, source = "ar", target = "en", original = "مرحبا", translation = "Hello", status = TurnStatus.COMPLETE)
        val swapped = ConversationSession(first = "ar", second = "en", turns = listOf(turn)).copy(first = "en", second = "ar")
        assertEquals("ar", swapped.turns.single().source)
        assertTrue(swapped.exportText().contains("مرحبا\nHello"))
    }
}
