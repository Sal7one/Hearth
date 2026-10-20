package com.sal7one.common_jni.translation

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class Ct2CatalogTest {
    @Test fun jisrPrecisionVariantsKeepDistinctPackagesAndOneDirectionalFamilyCard() {
        val jisr=Ct2Catalog.models.filter { it.id.startsWith("ct2-jisr-") }
        assertEquals(4,jisr.size)
        assertEquals(2,jisr.map { it.groupId }.distinct().size)
        assertEquals(setOf(2,3,4,5),jisr.map { it.tokenizerMode }.toSet())
        assertTrue(jisr.all { !it.downloadEnabled }) // Converted packages are supplied in the draft, not invented HF URLs.
        jisr.forEach { spec ->
            assertTrue(spec.files.map { it.name }.containsAll(listOf("model.bin","config.json","shared_vocabulary.json","tokenizer.json","LICENSE","NOTICE")))
            assertEquals(6,spec.files.size)
            assertTrue(spec.bytes < if(spec.precisionLabel=="INT8")60_000_000L else 200_000_000L)
        }
        assertEquals(14,JisrDialects.tags.size)
        assertEquals("apc",JisrDialects.tags["apc"])
        assertEquals("ajp",JisrDialects.tags["ajp"])
        assertEquals("ajp-ps",JisrDialects.tags["ajp-ps"])
    }
    @Test fun directionalPackagesHaveCompletePinsAndSmallLanguageAliases() {
        val en=Ct2Catalog.find("ct2-quickmt-en-ar")!!;val ar=Ct2Catalog.find("ct2-quickmt-ar-en")!!
        assertEquals(363_146_041L,en.bytes);assertEquals(410_561_529L,ar.bytes)
        assertTrue(en.supports("en-US","ar-SA"));assertFalse(en.supports("ar","en"))
        assertTrue(ar.supports("ar","en"));assertFalse(ar.supports("en","ar"))
        val small=Ct2Catalog.find("ct2-small100-int8")!!
        assertEquals(344_075_494L,small.bytes);assertEquals(100,small.sources.size)
        assertTrue(small.supports("tl","ar"));assertFalse(small.supports("auto","ar"))
        assertFalse(small.supports("ar","ar"));assertEquals(9900,small.directions.size)
        Ct2Catalog.models.forEach { spec ->
            assertEquals(spec.files.size,spec.files.map { it.name }.toSet().size)
            spec.files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")));assertTrue(it.revision.matches(Regex("[0-9a-f]{40}"))) }
        }
    }
    @Test fun missingTruncatedAndSameSizeCorruptionCannotPassPinnedVerification() {
        val root=Files.createTempDirectory("ct2-integrity").toFile()
        try {
            val bytes="valid tokenizer".toByteArray()
            val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val spec=Ct2Catalog.models.first().copy(files=listOf(Ct2File("config.json",bytes.size.toLong(),sha,"publisher/model","0".repeat(40))))
            assertThrows(IllegalStateException::class.java) { spec.verify(root) }
            val file=java.io.File(root,"config.json");file.writeBytes(bytes);spec.verify(root)
            file.writeBytes(bytes.copyOf(bytes.size-1));assertThrows(IllegalStateException::class.java) { spec.verify(root) }
            file.writeBytes(ByteArray(bytes.size));assertThrows(com.sal7one.common_jni.model.ModelIntegrityException::class.java) { spec.verify(root) }
        } finally { root.deleteRecursively() }
    }
}
