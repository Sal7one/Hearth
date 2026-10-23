package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.OcrSelection
import org.junit.Assert.*
import org.junit.Test

class ReadingContentTest {
    @Test fun invalidRememberedSourceDoesNotBypassCompatibilityChecks() {
        val invalid = OcrSelection("latin", "unsupported", "ar")
        val recovered = invalid.forReadingContent(ReadingContent.WEBSITES, "arabic", "ar")
        assertEquals("arabic", recovered.profileId); assertEquals("ar", recovered.source)
        assertEquals("ar", recovered.target)
        assertThrows(IllegalStateException::class.java) {
            invalid.forReadingContent(ReadingContent.WEBSITES, null, null)
        }
    }
    @Test fun websitesUseGenericReadersForEveryAdvertisedScriptAndKeepTranslationConfiguration() {
        val translator = OcrSelection("manga-koharu-clean-page", "ja", "ar", "libretranslate", true, "custom-translator")
        for ((language, expected) in listOf("en" to "latin", "ar" to "arabic", "zh" to "cjk", "ru" to "cyrillic")) {
            val selected = translator.forReadingContent(ReadingContent.WEBSITES, expected, language)
            assertEquals(expected, selected.profileId); assertEquals(language, selected.source)
            assertEquals(translator.target, selected.target); assertEquals(translator.providerId, selected.providerId)
            assertEquals(translator.localModelId, selected.localModelId); assertEquals(translator.translate, selected.translate)
            assertEquals(ReadingContent.WEBSITES, ReadingContent.selected(selected))
        }
    }

    @Test fun returningToMangaRestoresTheExplicitPreviousReaderAndSourceOnly() {
        val website = OcrSelection("arabic", "ar", "en", "local", false, "custom-translator")
        for (reader in listOf("manga", "manga-page", "manga-koharu-page", "manga-koharu-clean-page", "meiki")) {
            val manga = website.forReadingContent(ReadingContent.MANGA, reader, "ja")
            assertEquals(reader, manga.profileId); assertEquals("ja", manga.source)
            assertEquals("en", manga.target); assertFalse(manga.translate); assertEquals("custom-translator", manga.localModelId)
            val restored = manga.forReadingContent(ReadingContent.WEBSITES, website.profileId, website.source)
            assertEquals(website, restored)
        }
    }

    @Test fun selectingCurrentWorkflowNeverOverridesACompatibleCustomReader() {
        val multilingual = OcrSelection("arabic", "en", "ar")
        assertEquals(multilingual, multilingual.forReadingContent(ReadingContent.WEBSITES, "latin", "en"))
        val manga = OcrSelection("manga-koharu-clean-page", "ja", "ar")
        assertEquals(manga, manga.forReadingContent(ReadingContent.MANGA, "manga-page", "ja"))
    }

    @Test fun unknownOrWrongFamilyBookmarksFallBackToCompatibleModelsWithoutChangingDestination() {
        val manga = OcrSelection("manga-koharu-page", "ja", "ar")
        for ((profile, source) in listOf("deleted-model" to "en", "manga-koharu-page" to "ja", "arabic" to "ru", null to null)) {
            val web = manga.forReadingContent(ReadingContent.WEBSITES, profile, source)
            assertEquals("cjk", web.profileId); assertEquals("ja", web.source); assertEquals("ar", web.target)
        }
        val firstManga = OcrSelection().forReadingContent(ReadingContent.MANGA, "latin", "en")
        assertEquals("manga-page", firstManga.profileId); assertEquals("ja", firstManga.source); assertEquals("ar", firstManga.target)
    }
}
