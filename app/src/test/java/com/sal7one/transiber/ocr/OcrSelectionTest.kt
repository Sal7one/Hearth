package com.sal7one.transiber.ocr

import com.sal7one.transiber.translation.CloudTranslationLanguages
import com.sal7one.transiber.translation.TranslationOptions
import org.junit.Assert.*
import org.junit.Test

class OcrSelectionTest {
    @Test fun leavingArabicSelectsCompatibleReaderWithoutChangingDestination() {
        val original=OcrSelection("arabic","ar","en")
        val japanese=original.withSource("ja")
        assertEquals("cjk",japanese.profileId)
        assertEquals("ja",japanese.source)
        assertEquals("en",japanese.target)
        assertEquals("cyrillic",original.withSource("ru").profileId)
        assertEquals("latin",original.withSource("sv").profileId)
        assertTrue(runCatching { original.withSource("not-a-language") }.isFailure)
    }
    @Test fun japaneseReaderChoiceSurvivesSourceSelectionAndKeepsArabicDestination() {
        for(id in listOf("manga","meiki")) {
            val selection=OcrSelection(target="ar").withProfile(id).withSource("ja")
            assertEquals(id,selection.profileId)
            assertEquals("ja",selection.source)
            assertEquals("ar",selection.target)
            val targets=ocrTranslationTargets(selection,"hy-mt2-q4",null,true)
            assertTrue("ar" in targets);assertTrue("en" in targets);assertFalse("ja" in targets)
        }
    }
    @Test fun sourceWithinCurrentReaderPreservesIt() {
        assertEquals("latin",OcrSelection().withSource("sv").profileId)
        assertEquals("cjk",OcrSelection("cjk","ja","ar").withSource("en").profileId)
        assertEquals(OcrCatalog.profiles.flatMap {it.languages}.toSet(),OcrSelection.sourceLanguages)
    }
    @Test fun cloudDirectionsAreNotOcrDirectionsAndNeverFallBackToLocal() {
        val cloud=CloudTranslationLanguages(mapOf("ja" to "ja","ar" to "ar"),mapOf("en" to "en","ar" to "ar"),mapOf("ja" to setOf("en"),"ar" to setOf("en")))
        val selection=OcrSelection("manga","ja","ar","libretranslate")
        assertEquals(setOf("en"),ocrTranslationTargets(selection,"hy-mt2-q4",cloud,true))
        assertTrue(ocrTranslationTargets(selection,"hy-mt2-q4",null,true).isEmpty())
        assertTrue(ocrTranslationTargets(selection,"hy-mt2-q4",cloud,false).isEmpty())
        assertEquals("ar",selection.target) // Unsupported saved choices are explained, never silently replaced.
    }
    @Test fun offlineBuildAndUnknownModelsDoNotAdvertiseUnavailablePacks() {
        val selection=OcrSelection()
        assertTrue(ocrTranslationTargets(selection,TranslationOptions.ML_KIT,null,false).isEmpty())
        assertEquals(com.sal7one.transiber.translation.PlatformTranslation.available, "ar" in ocrTranslationTargets(selection,TranslationOptions.ML_KIT,null,true))
        assertTrue(ocrTranslationTargets(selection,"unknown",null,true).isEmpty())
    }
}
