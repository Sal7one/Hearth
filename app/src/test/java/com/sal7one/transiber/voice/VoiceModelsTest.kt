package com.sal7one.transiber.voice
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
class VoiceModelsTest {
    @Test fun readinessChecksTheRequestedGenderStyleRatherThanTheSavedVoice() {
        val dir = Files.createTempDirectory("voice-readiness-test").toFile()
        try {
            val models = VoiceModels(dir)
            // Sparse fixtures test readiness only; checksum verification still rejects them.
            (VoiceCatalog.core + VoiceCatalog.file("M3.json")).forEach { asset ->
                java.io.RandomAccessFile(models.file(asset), "rw").use { it.setLength(asset.bytes) }
            }
            assertTrue(models.ready("F3", VoiceGender.MALE))
            assertTrue(models.ready("M3"))
            assertFalse(models.ready("F3", VoiceGender.FEMALE))
            assertFalse(models.ready("F3"))
            assertThrows(IllegalStateException::class.java) { models.verify("M3") }
        } finally { dir.deleteRecursively() }
    }
    @Test fun downloadsArePinnedAndEveryVoiceHasItsStyle() {
        assertEquals(16,VoiceCatalog.supertonicAssets.size)
        assertEquals(VoiceCatalog.assets.size,VoiceCatalog.assets.map {it.id}.toSet().size)
        VoiceCatalog.supertonicAssets.forEach {assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")));assertTrue(it.bytes>0);assertTrue(it.url.startsWith("https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/"))}
        VoiceCatalog.voices.forEach {assertEquals("$it.json",VoiceCatalog.file("$it.json").filename)}
    }
    @Test fun recognizedMagpieRangeImportChecksPolicyBeforeInstallingAnyFiles() {
        var repository = java.io.File(System.getProperty("user.dir"))
        while (!java.io.File(repository, "settings.gradle.kts").exists() && repository.parentFile != null) repository = repository.parentFile
        val payload = java.io.File(repository, "build/voice-runtimes/models/tokenizer.ranges")
        org.junit.Assume.assumeTrue("Explicit authenticated tokenizer smoke fixture is unavailable", payload.isFile)
        val root = Files.createTempDirectory("voice-import-policy-").toFile()
        val rejection = IllegalStateException("Magpie runtime is unavailable in this process")
        var policyChecks = 0
        try {
            val models = VoiceModels(root) { policyChecks++; throw rejection }
            try {
                payload.inputStream().use { models.import(it) }
                fail("An unsupported process must not install a recognized Magpie component")
            } catch (received: IllegalStateException) { assertSame(rejection, received) }
            assertEquals(1, policyChecks)
            assertTrue("Rejected imports must remove staging and leave no tokenizer", root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    private fun zip(name: String,bytes: ByteArray):ByteArray=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {it.putNextEntry(ZipEntry(name));it.write(bytes);it.closeEntry()}}.toByteArray()
    @Test fun rejectedOrCancelledImportsLeaveNoPartialOrReadyModel() {
        val dir=Files.createTempDirectory("voice-import-test").toFile()
        try {
            val models=VoiceModels(dir)
            assertThrows(IllegalStateException::class.java){models.import(byteArrayOf(1,2,3).inputStream())}
            assertThrows(IllegalStateException::class.java){models.import(ByteArray(100).inputStream()){error("cancelled")}}
            assertFalse(models.ready("F1"));assertTrue(dir.listFiles().orEmpty().isEmpty())
            assertThrows(IllegalArgumentException::class.java){models.importZip(zip("../F1.json",byteArrayOf()).inputStream())}
            assertThrows(IllegalArgumentException::class.java){models.importZip(zip("/tmp/F1.json",byteArrayOf()).inputStream())}
            assertThrows(IllegalStateException::class.java){models.importZip(zip("wrong.onnx",byteArrayOf()).inputStream())}
            assertThrows(IllegalArgumentException::class.java){models.importZip(zip("LICENSE",ByteArray(65537)).inputStream())}
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {dir.deleteRecursively()}
    }
}
