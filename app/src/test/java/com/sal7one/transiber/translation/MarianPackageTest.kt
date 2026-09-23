package com.sal7one.transiber.translation

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MarianPackageTest {
    @Test fun publisherFilesFormOnePinnedSafePackage() {
        assertEquals(3, MarianPackage.pairs.size)
        assertEquals(3, MarianPackage.pairs.map { it.id }.toSet().size)
        MarianPackage.pairs.forEach { pair ->
            val parts = pair.parts
            assertEquals(4, parts.size)
            assertEquals(parts.size, parts.map { it.id }.toSet().size)
            assertEquals(parts.size, parts.map { it.fileName }.toSet().size)
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
