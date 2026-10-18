package com.sal7one.transiber.models

import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.downloads.DownloadAssets
import org.junit.Assert.*
import org.junit.Test

class VoskDownloadsTest {
    @Test fun officialModelsHaveDistinctArchiveAndTreePinsAndFixedLanguages() {
        assertEquals(setOf("en", "es", "fr", "de", "ru", "zh", "ar"), VoskDownloads.packages.map { it.language }.toSet())
        assertEquals(7, VoskDownloads.all.map { it.id }.distinct().size)
        VoskDownloads.packages.forEach { source ->
            val artifact = source.artifact
            assertEquals(SpeechArtifactKind.VOSK, artifact.kind)
            assertEquals(CaptionEngineChoice.VOSK, artifact.engine)
            assertEquals("https://alphacephei.com/vosk/models/${artifact.archiveRoot}.zip", artifact.url)
            assertEquals("Apache-2.0", artifact.license)
            assertEquals(64, source.treeSha256.length)
            assertNotEquals(artifact.sha256, source.treeSha256)
            assertEquals(source, VoskDownloads.find(artifact.id))
            assertEquals(source, VoskDownloads.findByTreeSha256(source.treeSha256))
            assertEquals(artifact.bytes + source.expandedBytes, artifact.installedBytes)
            if (source.language != "ar") assertTrue(artifact.bytes < 50_000_000L)
        }
        assertNull(VoskDownloads.find("not-a-model"))
        assertNull(VoskDownloads.findByTreeSha256(null))
        assertTrue(VoskDownloads.packages.single { it.language == "ar" }.artifact.advanced)
    }

    @Test fun eachVoskPackageHasThePinnedTransportIdentityAndFullInstallationReserve() {
        VoskDownloads.packages.forEach { source ->
            val artifact = source.artifact
            val download = checkNotNull(DownloadAssets.find(artifact.id)) { "Missing Vosk download identity: ${artifact.id}" }
            assertEquals(artifact.id, download.id)
            assertEquals(artifact.url, download.url)
            assertEquals(artifact.bytes, download.bytes)
            assertEquals(artifact.sha256, download.sha256)
            assertEquals(artifact.bytes + source.expandedBytes, download.installedBytes)
        }
    }
}
