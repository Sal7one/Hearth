package com.sal7one.hearth.tts.ming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MingCatalogTest {
    @Test fun bundlePinsMatchThePublishedRuntimeAndStayUnique() {
        assertEquals(17, MingCatalog.assets.size)
        assertEquals(15, MingCatalog.assets.count { Regex("^part-\\d{3}\\.bin$").matches(it.filename) })
        assertEquals(MingCatalog.assets.size, MingCatalog.assets.map { it.filename }.toSet().size)
        assertEquals(850_115_730L, MingCatalog.totalBytes)
        MingCatalog.assets.forEach { asset ->
            assertTrue(asset.bytes > 0)
            assertTrue("${asset.filename} hash", asset.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue("${asset.filename} url", asset.url.startsWith("https://huggingface.co/fusorlabs/ming-3.0-tts-0.5b-web/resolve/${MingCatalog.REVISION}/"))
        }
        assertEquals(
            "${MingCatalog.SOURCE}/resolve/${MingCatalog.REVISION}/parts/000.bin",
            MingCatalog.assets.first { it.filename == "part-000.bin" }.url,
        )
    }

    @Test fun onlyTheTwoLanguagesTheShippedRuntimeUsesAreAdvertised() {
        assertEquals(linkedSetOf("en", "zh"), MingCatalog.languages)
        assertEquals("en", MingCatalog.language("en-US"))
        assertEquals("zh", MingCatalog.language("zh-Hans"))
        assertNull(MingCatalog.language("ar"))
        assertNull(MingCatalog.language(""))
    }

    @Test fun voiceAllowlistCoversThePublishedPresetsAndLocalValidationIsRequired() {
        assertEquals(7, MingCatalog.voices.size)
        listOf("US_James", "US_Emma", "US_Stella", "UK_George", "UK_Charlotte", "灵小柔", "灵楷")
            .forEach { assertTrue(it, it in MingCatalog.voices) }
        assertEquals(MingCatalog.voices.size, MingCatalog.voices.toSet().size)
        assertEquals(300, MingCatalog.MAXIMUM_TEXT_CHARACTERS)
        assertEquals(44_100, MingCatalog.SAMPLE_RATE)
    }

    @Test fun rayonsStaysBoundedForTheOneGigabyteRuntime() {
        assertEquals(1, MingCatalog.defaultThreads(0))
        assertEquals(2, MingCatalog.defaultThreads(2))
        assertEquals(4, MingCatalog.defaultThreads(16))
    }

    @Test fun zipEntriesMapFromThePublishedPartsLayoutToFlatInstalledNames() {
        assertEquals("manifest.json", MingCatalog.flatFilename("manifest.json"))
        assertEquals("tokenizer.json", MingCatalog.flatFilename("bundle/tokenizer.json"))
        assertEquals("part-000.bin", MingCatalog.flatFilename("part-000.bin"))
        assertEquals("part-014.bin", MingCatalog.flatFilename("parts/014.bin"))
        assertEquals("part-003.bin", MingCatalog.flatFilename("ming-3.0-tts-0.5b-web/parts/003.bin"))
        assertEquals("part-003.bin", MingCatalog.flatFilename("bundle\\parts\\003.bin"))
        assertNull(MingCatalog.flatFilename("015.bin"))
        assertNull(MingCatalog.flatFilename("parts/README.md"))
        assertNull(MingCatalog.flatFilename(".gitattributes"))
        assertFalse(MingCatalog.flatFilename("parts/9999.bin") != null)
    }
}
