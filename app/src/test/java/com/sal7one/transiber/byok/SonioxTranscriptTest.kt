package com.sal7one.transiber.byok

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SonioxTranscriptTest {
    @Test fun interimIsReplacedAndCommittedRunsRetainTheirOwnLanguage() {
        val parser = SonioxTranscript()
        assertEquals("Прив", parser.accept(JSONObject("""{"tokens":[{"text":"Прив","is_final":false}]}""")).partial)
        assertEquals("Привет", parser.accept(JSONObject("""{"tokens":[{"text":"Привет","is_final":false}]}""")).partial)
        val a = parser.accept(JSONObject("""{"tokens":[{"text":"Привет","language":"ru","translation_status":"original","is_final":true}]}""")).updates.single()
        val b = parser.accept(JSONObject("""{"tokens":[{"text":"مرحبا","language":"ar","source_language":"ru","translation_status":"translation","is_final":true}]}""")).updates
        assertEquals(a.id, b[0].id); assertTrue(b[0].complete); assertFalse(b[0].translated)
        assertNotEquals(a.id, b[1].id); assertTrue(b[1].translated); assertEquals("ar", b[1].language)
        val end = parser.accept(JSONObject("""{"tokens":[{"text":"<end>","is_final":true}]}""")).updates.single()
        assertEquals(b[1].id, end.id); assertTrue(end.complete); assertEquals("مرحبا", end.text)
        assertTrue(parser.accept(JSONObject("""{"finished":true}""")).updates.isEmpty())
    }
    @Test fun partialTranslationDoesNotBecomeSourceOrDuplicateFinalWords() {
        val parser = SonioxTranscript()
        val first = parser.accept(JSONObject("""{"tokens":[{"text":"Hello","is_final":true},{"text":" world","is_final":false},{"text":"مرحبا","translation_status":"translation","is_final":false}]}"""))
        assertEquals("Hello", first.updates.single().text)
        assertEquals(" world", first.partial); assertEquals("مرحبا", first.translation)
        val second = parser.accept(JSONObject("""{"tokens":[{"text":" world","is_final":true},{"text":"<fin>","is_final":true}]}"""))
        assertEquals("Hello world", second.updates.single().text)
        assertEquals(first.updates.single().id, second.updates.single().id)
    }
}
