package com.sal7one.transiber.setup

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.models.PublisherSpeechReceipts
import com.sal7one.transiber.models.SpeechDownload
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest

class PublisherSpeechReceiptTest {
    @Test fun pinnedPublisherArchiveImportProducesTrustedOfflineReceiptWithoutDownloadManager() {
        val output = ByteArrayOutputStream()
        val roles = mapOf("model" to "tokens.txt", "encoder" to "encoder.ort", "decoder" to "decoder.ort")
        TarArchiveOutputStream(BZip2CompressorOutputStream(output)).use { tar -> roles.values.forEach { path ->
            val bytes = "fixture-$path".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("publisher/$path").apply { size = bytes.size.toLong() })
            tar.write(bytes); tar.closeArchiveEntry()
        } }
        val archive = output.toByteArray()
        val spec = SpeechDownload(SpeechProfile.MOONSHINE_TINY_EN, "https://example.org/pinned.tar.bz2", archive.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }, "publisher", roles)
        val root = Files.createTempDirectory("publisher-offline-receipt").toFile()
        try {
            val store = LocalSpeechModels(root)
            val installed = store.installPublisher(archive.inputStream(), spec, -71)
            val receipt = PublisherSpeechReceipts(root)
            val verified = SpeechModelPackage.verify(installed.root)
            assertTrue(receipt.matches(installed.id, spec, verified.manifestSha256))
            assertFalse(receipt.matches(installed.id, spec.copy(sha256 = "0".repeat(64)), verified.manifestSha256))
            assertEquals(listOf(installed), store.list())
            // A same-profile ZIP cannot supply or replace this installer-owned receipt.
            installed.root.resolve(SpeechModelPackage.MANIFEST).appendText(" ")
            val altered = ModelIntegrity.inspect(installed.root.resolve(SpeechModelPackage.MANIFEST)).digest.hex
            assertFalse(receipt.matches(installed.id, spec, altered))
            val repaired = store.installPublisher(archive.inputStream(), spec, -71)
            assertTrue(receipt.matches(repaired.id, spec, SpeechModelPackage.verify(repaired.root).manifestSha256))
        } finally { root.deleteRecursively() }
    }
}
