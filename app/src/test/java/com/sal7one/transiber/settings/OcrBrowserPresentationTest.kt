package com.sal7one.transiber.settings

import com.sal7one.transiber.ocr.OcrCatalog
import org.junit.Assert.*
import org.junit.Test

class OcrBrowserPresentationTest {
    @Test fun matchesLanguageAliasesAndExactExportNamesWithoutInventingCoverage() {
        val arabic = OcrCatalog.profile("arabic")
        assertTrue(arabic.matchesBrowser("Arabic Paddle", "any"))
        assertTrue(arabic.matchesBrowser("العَرَبِيَّة", "any"))
        assertTrue(arabic.matchesBrowser("arabic_PP-OCRv5_mobile_rec_onnx", "paddle"))
        assertFalse(arabic.matchesBrowser("Japanese", "any"))
        assertFalse(arabic.matchesBrowser("", "manga"))
    }

    @Test fun familyAndSearchBothConstrainResultsAcrossAllSixProfiles() {
        assertEquals(OcrCatalog.profiles.size, OcrCatalog.profiles.count { it.matchesBrowser("", "any") })
        assertEquals(setOf("manga", "meiki"), OcrCatalog.profiles.filter { it.matchesBrowser("experimental Japanese", "any") }.map { it.id }.toSet())
        assertEquals(listOf("manga"), OcrCatalog.profiles.filter { it.matchesBrowser("encoder_model_uint8", "manga") }.map { it.id })
        assertTrue(OcrCatalog.profiles.none { it.matchesBrowser("does-not-exist", "any") })
    }
}
