package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.magpie.MagpieCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoiceImportSourcesTest {
    @Test fun sourceBindingsFollowFinalTokenizerMembersRatherThanDeletedRangePayload() {
        val root=File("/private-test-voice-models");val models=VoiceModels(root)
        val payload=VoiceAsset("voice-magpie-tokenizer","tokenizer.ranges","tokenizer.ranges",1,"","")
        val bindings=voiceImportSources(models,payload)
        assertEquals(MagpieCatalog.tokenizer.size,bindings.size)
        MagpieCatalog.tokenizer.forEach { asset ->
            assertEquals(File(root,"magpie/tokenizer/${asset.filename}"),bindings.toMap()["voice-magpie-tokenizer-${asset.filename}"])
        }
        assertFalse(bindings.any { it.second.name=="tokenizer.ranges" })
    }
    @Test fun rejectedZipBytesNeverPublishASourceBinding() {
        val root=java.nio.file.Files.createTempDirectory("voice-source-reject").toFile()
        try {
            val output=java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use {zip -> zip.putNextEntry(java.util.zip.ZipEntry("F1.json"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()}
            val published=mutableListOf<VoiceAsset>()
            assertThrows(IllegalStateException::class.java) {
                VoiceModels(root).importZip(output.toByteArray().inputStream(),onImported={published+=it})
            }
            assertTrue(published.isEmpty());assertTrue(root.listFiles()!!.isEmpty())
        }finally{root.deleteRecursively()}
    }
    @Test fun optionalFamilyBindingsKeepTheirOwnDirectoryAndStableCatalogIdentity() {
        val root=File("/private-test-voice-models");val models=VoiceModels(root)
        val asset=VoiceCatalog.outeAssets.first();val binding=voiceImportSources(models,asset).single()
        assertEquals(asset.id,binding.first);assertEquals(File(root,"oute/${asset.filename}"),binding.second)
    }
}
