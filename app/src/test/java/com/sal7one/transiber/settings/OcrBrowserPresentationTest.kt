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

    @Test fun englishInputUsesLatinEvenWhenArabicIsTheDesiredOutputOrCurrentScriptChoice() {
        assertEquals("latin", recommendedOcrReader("en", "arabic")?.id)
        assertEquals("arabic", recommendedOcrReader("ar", "latin")?.id)
        assertEquals("cyrillic", recommendedOcrReader("ru", "latin")?.id)
        assertEquals("cjk", recommendedOcrReader("zh", "latin")?.id)
        assertNull(recommendedOcrReader("not-supported", "arabic"))
    }

    @Test fun recommendationsKeepExplicitJapaneseMangaChoicesAndNeverChangeModelCoverage() {
        for (id in listOf("manga", "manga-page", "manga-koharu-page", "manga-koharu-clean-page", "meiki")) {
            assertEquals(id, recommendedOcrReader("ja", id)?.id)
        }
        assertEquals("cjk", recommendedOcrReader("ja", "latin")?.id)
        for (code in com.sal7one.transiber.ocr.OcrSelection.sourceLanguages) {
            val reader = checkNotNull(recommendedOcrReader(code, "latin"))
            assertTrue(code in reader.languages)
        }
    }

    @Test fun persistedFamilySearchAndInstalledFiltersExposeHiddenUsefulReadersWithoutResettingTheFilters() {
        val query = "Koharu"
        val family = "manga"
        val shown = OcrCatalog.profiles.filter { it.matchesBrowser(query, family) }
        assertTrue(shown.isNotEmpty())
        assertTrue(hiddenRelevantOcrReaders("en", "arabic", shown) > 0)
        assertEquals(4, hiddenRelevantOcrReaders("en", "arabic", emptyList()))
        assertEquals(0, hiddenRelevantOcrReaders("en", "arabic", OcrCatalog.profiles))
        assertEquals("Koharu", query); assertEquals("manga", family)
        assertFalse(shown.any { it.id == "latin" || it.id == "arabic" })
    }
}
