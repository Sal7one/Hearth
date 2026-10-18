package com.sal7one.transiber.translation

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MarianPackageTest {
    @Test fun publisherFilesFormOnePinnedSafePackage() {
        assertEquals(12, MarianPackage.pairs.size)
        assertEquals(12, MarianPackage.pairs.map { it.id }.toSet().size)
        MarianPackage.pairs.forEach { pair ->
            val parts = pair.parts
            assertEquals(4, parts.size)
            assertEquals(parts.size, parts.map { it.id }.toSet().size)
            assertEquals(parts.size, parts.map { it.fileName }.toSet().size)
            assertTrue(pair.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(parts.all { it.bytes > 0 && it.installedBytes == pair.downloadBytes })
            assertTrue("A pair should stay below 250 MiB", pair.downloadBytes < 250L * 1024 * 1024)
            parts.forEach { part ->
                assertTrue(part.fileName.matches(Regex("[A-Za-z0-9_.-]+")))
                assertTrue(part.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(part.url.startsWith("https://huggingface.co/${pair.repository}/resolve/${pair.revision}/"))
            }
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update("model-tree-sha256-v1\u0000".toByteArray(Charsets.US_ASCII))
            parts.sortedBy { it.fileName }.forEach { part ->
                val name = part.fileName.toByteArray(Charsets.UTF_8)
                hash.update(java.nio.ByteBuffer.allocate(8).putLong(name.size.toLong()).array())
                hash.update(name)
                hash.update(java.nio.ByteBuffer.allocate(8).putLong(part.bytes).array())
                hash.update(part.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            assertEquals(pair.treeSha256, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        assertEquals(246951100L, MarianPackage.pairs[0].downloadBytes)
    }

    @Test fun lightweightPairsHaveExactDirectionsAndDistinctDownloads() {
        val expected = setOf("en-ar", "ar-en", "ru-en", "en-ru", "zh-en", "en-zh",
            "en-fr", "fr-en", "en-de", "de-en", "en-es", "es-en")
        assertEquals(expected, MarianPackage.pairs.map { "${it.source}-${it.target}" }.toSet())
        val downloads = MarianPackage.pairs.flatMap { it.parts }
        assertEquals(downloads.size, downloads.map { it.id }.toSet().size)
        assertEquals(downloads.size, downloads.map { it.downloadName }.toSet().size)
        downloads.forEach { part ->
            assertEquals(part, MarianPackage.part(part.id))
            assertEquals(part.pairId, MarianPackage.pairForPart(part.id)?.id)
        }
        // Existing downloaded originals retain their public names and pins.
        assertEquals("source.spm", MarianPackage.find("marian-en-ar")!!.parts[0].downloadName)
        assertEquals("marian-ar-en-source.spm", MarianPackage.find("marian-ar-en")!!.parts[0].downloadName)
        assertEquals("95a719c4c037310789b44358a459cd4b10a2cd187944b21101d4531d290e225b",
            MarianPackage.find("marian-en-ar")!!.treeSha256)
        assertEquals("d083f647b1fe640dbbbfcfe2372cf34926fd45e7eb6f0da89775ebaa79fa1768",
            MarianPackage.find("marian-ru-en")!!.treeSha256)
        assertEquals("2338de6a833df68301890c637145fe7c1b67cbac13ede10f1f2c21477931802c",
            MarianPackage.find("marian-zh-en")!!.treeSha256)
        assertEquals("CC-BY-4.0", MarianPackage.find("marian-en-de")!!.originalLicense)
        assertEquals("Apache-2.0", MarianPackage.find("marian-de-en")!!.originalLicense)
        assertTrue(MarianPackage.find("marian-en-de")!!.license.contains("CC-BY-4.0 (original"))
    }

    @Test fun exactlyOnePublishedPairIsSelectable() {
        MarianPackage.pairs.forEach { pair ->
            assertTrue(TranslationOptions.supports(pair.id, pair.source, pair.target))
            assertFalse(TranslationOptions.supports(pair.id, pair.target, pair.source))
            assertFalse(TranslationOptions.supports(pair.id, pair.source, pair.source))
            assertEquals(setOf(pair.source), TranslationOptions.sourceLanguages(pair.id))
            assertEquals(setOf(pair.target), TranslationOptions.targetLanguages(pair.id))
        }
        assertFalse(TranslationOptions.supports(TranslationOptions.MARIAN_RU_EN, "ru", "ar"))
    }
}
