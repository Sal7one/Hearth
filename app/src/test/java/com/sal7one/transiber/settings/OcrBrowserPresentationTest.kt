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

    @Test fun familyAndSearchBothConstrainResultsIncludingAutomaticPages() {
        assertEquals(OcrCatalog.profiles.size, OcrCatalog.profiles.count { it.matchesBrowser("", "any") })
        assertEquals(setOf("manga-page", "manga-koharu-page", "manga-koharu-clean-page", "manga", "meiki"), OcrCatalog.profiles.filter { it.matchesBrowser("experimental Japanese", "any") }.map { it.id }.toSet())
        assertEquals(listOf("manga-page", "manga-koharu-page", "manga-koharu-clean-page", "manga"), OcrCatalog.profiles.filter { it.matchesBrowser("encoder_model_uint8", "manga") }.map { it.id })
        assertEquals(listOf("manga-page", "manga-koharu-page", "manga-koharu-clean-page"), OcrCatalog.profiles.filter { it.matchesBrowser("detector-v4-s", "manga") }.map { it.id })
        assertEquals(listOf("manga-koharu-clean-page"), OcrCatalog.profiles.filter {it.matchesBrowser("LaMa", "manga")}.map {it.id})
        assertEquals(listOf("manga-koharu-page", "manga-koharu-clean-page"), OcrCatalog.profiles.filter {it.matchesBrowser("academic", "manga")}.map {it.id})
        assertTrue(OcrCatalog.profiles.none { it.matchesBrowser("does-not-exist", "any") })
    }
}
