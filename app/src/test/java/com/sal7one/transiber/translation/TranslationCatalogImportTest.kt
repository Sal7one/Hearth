package com.sal7one.transiber.translation

import com.sal7one.common_jni.translation.TranslationCatalog
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class TranslationCatalogImportTest {
    @Test fun batchIdentificationUsesTheDigestAndSizeRatherThanAChosenFileName() {
        val root = Files.createTempDirectory("catalog-import").toFile()
        try {
            val bytes = "GGUF fixture bytes".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val spec = TranslationCatalog.models.first().copy(id = "fixture", bytes = bytes.size.toLong(), sha256 = hash)
            val other = spec.copy(id = "other", sha256 = "0".repeat(64))
            val store = LocalTranslationModels(root)
            val (identified, file) = store.importCatalog(bytes.inputStream(), listOf(other, spec))
            assertEquals(spec, identified)
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(store.file(other).exists())
            file.writeBytes("damaged".toByteArray())
            assertEquals(file, store.importCatalog(bytes.inputStream(), listOf(spec)).second)
            assertArrayEquals(bytes, file.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun unknownOrOversizeBytesNeverBecomeSelectable() {
        val root = Files.createTempDirectory("catalog-import-reject").toFile()
        try {
            val spec = TranslationCatalog.models.first().copy(id = "fixture", bytes = 12, sha256 = "0".repeat(64))
            val store = LocalTranslationModels(root)
            assertThrows(Exception::class.java) { store.importCatalog("unknown file".byteInputStream(), listOf(spec)) }
            assertThrows(Exception::class.java) { store.importCatalog(ByteArray(13).inputStream(), listOf(spec)) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
