package com.sal7one.hearth.desktop.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeechLanguageGuardTest {
    @Test fun unsupportedExplicitLanguageNeverCreatesAnEngine() {
        var creates = 0
        for (languages in listOf(setOf("en"), setOf("en", "nl", "fr", "de", "it", "pl", "pt", "es"))) {
            assertFailsWith<IllegalArgumentException> { withSupportedSpeechLanguage("ar-SA", languages, "checkpoint") { creates++; it } }
        }
        assertEquals(0, creates)
    }
    @Test fun bcp47AndAutomaticDeclarationsAreNormalizedWithoutInventingDetection() {
        assertEquals("en", withSupportedSpeechLanguage("EN-us", setOf("en"), "checkpoint") { it })
        assertEquals("pt", withSupportedSpeechLanguage("pt_BR", setOf("pt"), "checkpoint") { it })
        assertEquals("auto", withSupportedSpeechLanguage("Auto", setOf("en"), "checkpoint") { it })
    }
}
