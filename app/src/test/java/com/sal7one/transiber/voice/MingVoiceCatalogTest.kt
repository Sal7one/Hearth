package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.ming.MingCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MingVoiceCatalogTest {
    @Test fun theAppLibraryRegistersEveryPinnedMingComponentAsItsOwnFamily() {
        assertEquals(17, VoiceCatalog.mingAssets.size)
        assertEquals(VoiceCatalog.mingAssets, VoiceCatalog.familyAssets(MingCatalog.FAMILY))
        assertEquals(MingCatalog.totalBytes, VoiceCatalog.mingAssets.sumOf { it.bytes })
        assertEquals(MingCatalog.assets.size, VoiceCatalog.mingAssets.map { it.id }.toSet().size)
        VoiceCatalog.mingAssets.forEach { asset ->
            assertEquals(MingCatalog.FAMILY, asset.family)
            assertEquals(asset.filename, asset.installedPath)
            assertEquals(asset.filename, asset.path)
            assertEquals("MIT", asset.license)
            assertTrue(asset.url.startsWith("https://"))
            assertTrue(asset.bytes > 0)
            assertTrue(asset.sha256.matches(Regex("[a-f0-9]{64}")))
        }
        assertEquals(VoiceCatalog.assets.size, VoiceCatalog.assets.map { it.id }.toSet().size)
    }

    @Test fun zipNamesFromThePublishedLayoutResolveToARegisteredMingAsset() {
        val registered = VoiceCatalog.mingAssets.map { it.filename }.toSet()
        MingCatalog.assets.forEach { asset ->
            val mapped = MingCatalog.flatFilename(asset.filename)
            assertTrue("${asset.filename} did not map", mapped != null)
            assertTrue("$mapped is not registered", mapped in registered)
        }
        assertEquals("part-014.bin", MingCatalog.flatFilename("parts/014.bin"))
        assertEquals("tokenizer.json", MingCatalog.flatFilename("bundle/tokenizer.json"))
    }

    @Test fun experimentalMingGateIsOffUnlessExplicitlyStored() {
        assertFalse(MingLocalFeature.isEnabled(null))
        assertFalse(MingLocalFeature.isEnabled(false))
        assertTrue(MingLocalFeature.isEnabled(true))
    }

    @Test fun theProviderRegistryExposesMingOnlyThroughTheFamilyDescriptor() {
        assertTrue(MingCatalog.FAMILY in LocalVoiceFamilies.ids)
        assertEquals(MingCatalog.voices, LocalVoiceFamilies.voices(MingCatalog.FAMILY))
        assertEquals(MingCatalog.languages, LocalVoiceFamilies.languages(MingCatalog.FAMILY))
        assertFalse(LocalVoiceFamilies.verifiedGender(MingCatalog.FAMILY))
        assertEquals("Ming-3.0 TTS 0.5B · experimental", LocalVoiceFamilies.label(MingCatalog.FAMILY))
    }

    @Test fun engineLanguagesRemainEnglishAndChineseOnly() {
        assertEquals(linkedSetOf("en", "zh"), MingCatalog.languages)
        assertFalse("ar" in MingCatalog.languages)
        assertFalse("de" in MingCatalog.languages)
    }
}
