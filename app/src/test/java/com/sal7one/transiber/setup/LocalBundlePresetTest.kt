package com.sal7one.transiber.setup

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.translation.MarianPackage
import org.junit.Assert.*
import org.junit.Test

class LocalBundlePresetTest {
    @Test fun compactCaptionsRemainSmallAndDoNotRequestTranslationOrVoice() {
        val bundle = checkNotNull(LocalBundlePresets.create(LocalBundleKind.COMPACT_CAPTIONS, "ar", "en"))
        assertEquals("whisper-tiny-q5_1", bundle.setup.speech?.model?.catalogId)
        assertEquals("ar", bundle.setup.speech?.sourceLanguage)
        assertNull(bundle.setup.translation)
        assertNull(bundle.setup.voice)
        assertTrue(bundle.downloadBytes < 40L * 1024 * 1024)
        assertEquals(checkNotNull(bundle.speech).bytes, bundle.downloadBytes)
    }

    @Test fun compactTranslationUsesOnlyADirectPairAndReportsItsFullSize() {
        for (pair in MarianPackage.pairs) {
            val bundle = checkNotNull(LocalBundlePresets.create(LocalBundleKind.FAST_TRANSLATION, pair.source, pair.target))
            assertTrue(pair.source in checkNotNull(bundle.speech).languages)
            assertEquals(pair.id, bundle.setup.translation?.modelId)
            assertEquals(pair.source, bundle.setup.translation?.sourceLanguage)
            assertEquals(pair.target, bundle.setup.translation?.targetLanguage)
            assertEquals(pair.downloadBytes + checkNotNull(bundle.speech).bytes, bundle.downloadBytes)
            assertNull(bundle.setup.voice)
        }
        assertNull(LocalBundlePresets.create(LocalBundleKind.FAST_TRANSLATION, "ar", "ru"))
        assertNull(LocalBundlePresets.create(LocalBundleKind.FAST_TRANSLATION, "en", "en"))
    }

    @Test fun broadSpeechRejectsUnsupportedSourceRatherThanClaimingTranslatorCoverageAsAsrCoverage() {
        val bundle = checkNotNull(LocalBundlePresets.create(LocalBundleKind.BROAD_TRANSLATION, "ar", "en"))
        assertEquals("nemotron-3.5-asr-0.6b", bundle.setup.speech?.model?.catalogId)
        assertEquals(LocalBundlePresets.BROAD_TRANSLATOR, bundle.setup.translation?.modelId)
        val translator = TranslationCatalog.find(LocalBundlePresets.BROAD_TRANSLATOR)
        assertEquals(checkNotNull(bundle.speech).bytes + translator.bytes, bundle.downloadBytes)
        assertTrue(bundle.downloadBytes > 1024L * 1024 * 1024)
        assertNull(LocalBundlePresets.create(LocalBundleKind.BROAD_TRANSLATION, "fa", "en"))
        assertNull(LocalBundlePresets.create(LocalBundleKind.BROAD_TRANSLATION, "ar", "zz"))
    }

    @Test fun typedTranslationNeedsNoSpeechModelAndPreservesItsSeparateStage() {
        val bundle = checkNotNull(LocalBundlePresets.create(LocalBundleKind.FAST_TRANSLATION, "en", "ar", translationOnly = true))
        assertNull(bundle.setup.speech)
        assertNull(bundle.setup.voice)
        assertEquals("marian-en-ar", bundle.setup.translation?.modelId)
        assertEquals(checkNotNull(MarianPackage.find("marian-en-ar")).downloadBytes, bundle.downloadBytes)
        assertNull(LocalBundlePresets.create(LocalBundleKind.COMPACT_CAPTIONS, "en", "ar", translationOnly = true))
        val broad = checkNotNull(LocalBundlePresets.create(LocalBundleKind.BROAD_TRANSLATION, "fa", "en", translationOnly = true))
        assertEquals(TranslationCatalog.find(LocalBundlePresets.BROAD_TRANSLATOR).bytes, broad.downloadBytes)
    }

    @Test fun quickPresetsPickExactLanguageAndKeepWindowedSpeechDistinctFromStreaming() {
        fun preset(kind: LocalBundleKind, language: String) =
            LocalBundlePresets.create(kind, language, "ar")?.speech?.profile
        assertEquals(SpeechProfile.MOONSHINE_TINY_EN, preset(LocalBundleKind.SHORT_CAPTIONS, "en"))
        assertEquals(SpeechProfile.MOONSHINE_BASE_AR, preset(LocalBundleKind.SHORT_CAPTIONS, "ar"))
        assertEquals(SpeechProfile.PHONON_2_5, preset(LocalBundleKind.PHONON_CAPTIONS, "ru"))
        assertNull(preset(LocalBundleKind.PHONON_CAPTIONS, "ar"))
        assertEquals(SpeechProfile.NEMOTRON_3_5_ASR_0_6B, preset(LocalBundleKind.STREAMING_CAPTIONS, "en"))
        val englishToArabic = checkNotNull(LocalBundlePresets.create(LocalBundleKind.FAST_TRANSLATION, "en", "ar"))
        assertEquals(SpeechProfile.MOONSHINE_TINY_EN, englishToArabic.speech?.profile)
    }

    @Test fun quickMtPresetRequiresArmRuntimeAndKeepsItsDirection() {
        assertNull(LocalBundlePresets.create(LocalBundleKind.QUICKMT_TRANSLATION, "ja", "en"))
        val bundle = checkNotNull(LocalBundlePresets.create(
            LocalBundleKind.QUICKMT_TRANSLATION, "ja", "en", translationOnly = true, supportsCt2 = true))
        assertEquals("ct2-quickmt-ja-en-v2", bundle.translatorId)
        assertEquals(Ct2Catalog.find(checkNotNull(bundle.translatorId))?.bytes, bundle.downloadBytes)
        assertNull(bundle.speech)
        assertNull(LocalBundlePresets.create(LocalBundleKind.QUICKMT_TRANSLATION, "en", "ja", supportsCt2 = true))
    }

    @Test fun everyRecipeRetainsAnImmutableCatalogReferenceAcrossJsonExport() {
        for (kind in LocalBundleKind.entries) {
            val bundle = checkNotNull(LocalBundlePresets.create(kind, "en", "ar", supportsCt2 = true))
            val speech = checkNotNull(bundle.setup.speech)
            val artifact = checkNotNull(SpeechArtifactCatalog.find(checkNotNull(speech.model.catalogId)))
            assertEquals(artifact.family, speech.model.family)
            assertEquals(bundle.setup, PortableSetup.parse(bundle.setup.toJson()))
            assertNull(speech.model.sha256)
        }
    }
}
