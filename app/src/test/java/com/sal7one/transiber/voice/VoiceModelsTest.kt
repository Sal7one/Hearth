package com.sal7one.transiber.voice
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
class VoiceModelsTest {
    @Test fun downloadsArePinnedAndEveryVoiceHasItsStyle() {
        assertEquals(16,VoiceCatalog.assets.size)
        assertEquals(VoiceCatalog.assets.size,VoiceCatalog.assets.map {it.id}.toSet().size)
        VoiceCatalog.assets.forEach {assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")));assertTrue(it.bytes>0);assertTrue(it.url.startsWith("https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/"))}
        VoiceCatalog.voices.forEach {assertEquals("$it.json",VoiceCatalog.file("$it.json").filename)}
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
