package com.sal7one.transiber.translation

import org.junit.Assert.*
import org.junit.Test

class TranslatorChoiceVisibilityTest {
    @Test fun defaultPickerContainsInstalledChoicesAndKeepsAMissingCurrentChoiceRepairable() {
        assertTrue(localTranslatorVisible(true, false, false, "", "Whisper"))
        assertTrue(localTranslatorVisible(false, true, false, "", "Marian"))
        assertFalse(localTranslatorVisible(false, false, false, "", "Marian"))
        assertTrue(localTranslatorVisible(false, false, true, "", "Marian"))
    }
    @Test fun aLanguageOrNameQueryNeverBringsUnavailableModelsIntoTheDefaultPicker() {
        assertFalse(localTranslatorVisible(false, false, false, "Arabic", "Arabic Marian"))
        assertTrue(localTranslatorVisible(true, false, false, "marian Arabic", "Marian", "Arabic"))
        assertFalse(localTranslatorVisible(true, false, false, "Japanese", "Arabic Marian"))
    }
}
