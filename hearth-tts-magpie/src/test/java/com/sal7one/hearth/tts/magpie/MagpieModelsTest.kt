package com.sal7one.hearth.tts.magpie

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MagpieModelsTest {
    @Test fun pinnedInventoryIncludesFullTokenizerAndCorrectedSpeakerOrder() {
        assertEquals(listOf("Aria","Jason","John","Leo","Sofia"),MagpieCatalog.speakers)
        assertEquals(10,MagpieCatalog.tokenizer.size)
        assertEquals("ar-MSA",MagpieCatalog.nativeLanguage("ar"))
        assertEquals("ar-SA",MagpieCatalog.nativeLanguage("ar-SA"))
        assertEquals("pt-BR",MagpieCatalog.nativeLanguage("pt"))
        assertEquals(16,MagpieCatalog.archiveMembers.size)
        assertEquals(647486496L,MagpieCatalog.weights.sumOf {it.bytes})
        assertEquals(678563360L,MagpieCatalog.downloadBytes)
        (MagpieCatalog.weights+MagpieCatalog.tokenizer+MagpieCatalog.archiveMembers).forEach {
            assertTrue(it.bytes>0);assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertFalse(it.filename.contains('/'));assertFalse(it.filename.contains(".."))
        }
        assertTrue("ar" in MagpieCatalog.languages && "en" in MagpieCatalog.languages)
        assertFalse("zh" in MagpieCatalog.languages || "ja" in MagpieCatalog.languages)
    }
    @Test fun invalidInputNeverLeavesAnInstalledModel() {
        val root=Files.createTempDirectory("magpie-invalid-").toFile()
        try {
            val models=MagpieModels(root)
            assertFalse(models.ready())
            assertThrows(IllegalArgumentException::class.java){models.importWeight(byteArrayOf(1,2,3).inputStream(),MagpieCatalog.weights.first())}
            assertThrows(IllegalStateException::class.java){models.verify()}
            val bad=File(root,"bad.ranges").apply {writeBytes(byteArrayOf(0))}
            assertThrows(IllegalArgumentException::class.java){models.installTokenizerSegments(bad)}
            assertFalse(File(root,"tokenizer").exists())
            assertTrue(root.listFiles()!!.none {it.extension=="part"})
        }finally{root.deleteRecursively()}
    }
    @Test fun realPinnedTokenizerRangesInstallOnlyFinalVerifiedMembers() {
        var repository=File(System.getProperty("user.dir"))
        while(!File(repository,"settings.gradle.kts").exists() && repository.parentFile!=null)repository=repository.parentFile
        val source=File(repository,"build/voice-runtimes/models/tokenizer.ranges")
        assumeTrue("Run scripts/voice/fetch-magpie-test-assets.py for the real source-format smoke",source.isFile)
        val root=Files.createTempDirectory("magpie-real-tokenizer-").toFile()
        try {
            MagpieModels(root).installTokenizerSegments(source)
            val files=File(root,"tokenizer").listFiles()!!.toList()
            assertEquals(MagpieCatalog.tokenizer.map {it.filename}.toSet(),files.map {it.name}.toSet())
            MagpieCatalog.tokenizer.forEach { asset ->assertEquals(asset.bytes,File(root,"tokenizer/${asset.filename}").length()) }
            assertEquals(8358L,File(root,"tokenizer/model_config.yaml").length())
            assertFalse(File(root,"tokenizer.old").exists())
            MagpieModels(root).installTokenizerSegments(source)
            assertEquals(10,File(root,"tokenizer").listFiles()!!.size)
        }finally{root.deleteRecursively()}
    }
}
