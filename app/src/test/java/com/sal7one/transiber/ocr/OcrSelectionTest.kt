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
        for(id in listOf("manga","manga-page","manga-koharu-page","manga-koharu-clean-page","meiki")) {
            val selection=OcrSelection(target="ar").withProfile(id).withSource("ja")
            assertEquals(id,selection.profileId)
            assertEquals("ja",selection.source)
            assertEquals("ar",selection.target)
            val targets=ocrTranslationTargets(selection,"hy-mt2-q4",null,true)
            assertTrue("ar" in targets);assertTrue("en" in targets);assertFalse("ja" in targets)
        }
    }
    @Test fun mangaLayoutChoicesSelectTheRequestedBundleWithoutChangingDestination() {
        val original=OcrSelection(target="ar").withProfile("manga-koharu-clean-page")
        for(mode in MangaPageMode.entries) {
            val next=original.withMangaPageMode(mode)
            assertEquals(mode.profileId,next.profileId)
            assertEquals(mode,next.profile.mangaPageMode)
            assertEquals("ar",next.target);assertEquals("ja",next.source)
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
    @Test fun explicitReaderTranslatorSurvivesCaptionModelAndReaderChanges() {
        val legacy=OcrSelection()
        assertEquals("hy-mt2-q4",legacy.translationModel("hy-mt2-q4"))
        val selected=legacy.copy(localModelId="hy-mt2-q4")
        assertEquals("hy-mt2-q4",selected.withSource("ja").withProfile("manga").translationModel("different-caption-model"))
        assertEquals("hy-mt2-q4",selected.copy(providerId="libretranslate").translationModel("different-caption-model"))
    }
    @Test fun blankLegacyOverrideFollowsTheSavedSharedModelAndAnExplicitChoiceRemainsIndependent() {
        for(blank in listOf(null,""," ","  ")) {
            val legacy=OcrSelection(source="en",target="ar",localModelId=blank)
            assertEquals("hy-mt2-q4",legacy.translationModel("hy-mt2-q4"))
            assertEquals("marian-en-ar",legacy.translationModel("marian-en-ar"))
            assertNull(ocrTranslationProblem(legacy,legacy.translationModel("hy-mt2-q4"),null,true))
        }
        val explicit=OcrSelection(source="en",target="ar").withTranslator("local","marian-en-ar")
        assertEquals("marian-en-ar",explicit.translationModel("hy-mt2-q4"))
        assertEquals("en",explicit.source);assertEquals("ar",explicit.target);assertEquals("latin",explicit.profileId)
        assertEquals("hy-mt2-q4",explicit.withTranslator("local","").translationModel("hy-mt2-q4"))
        val unknown=explicit.withTranslator("local","removed-custom-model")
        assertEquals("removed-custom-model",unknown.translationModel("hy-mt2-q4"))
        assertEquals(OcrTranslationProblem.UNKNOWN_MODEL,ocrTranslationProblem(unknown,unknown.translationModel("hy-mt2-q4"),null,true))
    }

    @Test fun englishArabicUsesTheImageDirectionForEveryAdvertisedLocalFamily() {
        val selection=OcrSelection(source="en",target="ar")
        val advertised=com.sal7one.common_jni.translation.TranslationCatalog.models.filter {it.supports("en","ar")}.map {it.id}+
            com.sal7one.common_jni.translation.Ct2Catalog.models.filter {it.supports("en","ar")}.map {it.id}+TranslationOptions.MARIAN_EN_AR
        assertTrue(advertised.size>5)
        for(id in advertised) {
            assertTrue("$id must offer its published English→Arabic direction","ar" in ocrTranslationTargets(selection,id,null,true))
            assertNull("$id must use the chosen image source, independent of speech auto detection",ocrTranslationProblem(selection,id,null,true))
        }
        assertEquals(OcrTranslationProblem.EXPLICIT_SOURCE,ocrTranslationProblem(selection.copy(source="auto"),"hy-mt2-q4",null,true))
        assertEquals(OcrTranslationProblem.UNSUPPORTED_PAIR,ocrTranslationProblem(selection,TranslationOptions.MARIAN_RU_EN,null,true))
    }

    @Test fun missingConfigurationIsExplainedSeparatelyFromAnUnsupportedDirection() {
        val selected=OcrSelection(source="en",target="ar")
        assertEquals(OcrTranslationProblem.CHOOSE_MODEL,ocrTranslationProblem(selected,"",null,true))
        assertEquals(OcrTranslationProblem.CLOUD_LANGUAGES,ocrTranslationProblem(selected.copy(providerId="libretranslate"),"hy-mt2-q4",null,true))
        assertEquals(OcrTranslationProblem.UNKNOWN_PROVIDER,ocrTranslationProblem(selected.copy(providerId="deleted-server"),"hy-mt2-q4",null,true))
        assertEquals(OcrTranslationProblem.OFFLINE_PROVIDER,ocrTranslationProblem(selected.copy(providerId="libretranslate"),"hy-mt2-q4",null,false))
        assertEquals(OcrTranslationProblem.SAME_LANGUAGE,ocrTranslationProblem(selected.copy(target="en"),"hy-mt2-q4",null,true))
        assertNull(ocrTranslationProblem(selected.copy(translate=false,providerId="deleted-server"),"",null,false))
        val cloud=CloudTranslationLanguages(mapOf("en" to "en"),mapOf("ar" to "ar"))
        assertNull(ocrTranslationProblem(selected.copy(providerId="libretranslate"),"unknown-unused-local",cloud,true))
    }

}
