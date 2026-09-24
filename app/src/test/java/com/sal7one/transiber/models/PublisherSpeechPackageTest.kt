package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest

class PublisherSpeechPackageTest {
    private val roles = mapOf("model" to "tokens.txt", "encoder" to "encoder_model.ort", "decoder" to "decoder_model_merged.ort")
    private val defaults = roles.values.map { "fixture/$it" to "asset".toByteArray() }
    private fun archive(entries: List<Pair<String, ByteArray>> = defaults, link: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(output)).use { tar ->
            entries.forEachIndexed { index, (name, bytes) ->
                val entry = if (link && index == 0) TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = "/outside" }
                    else TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(entry)
                if (!entry.isSymbolicLink) tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }
    private fun spec(bytes: ByteArray) = SpeechDownload(SpeechProfile.MOONSHINE_TINY_EN, "https://example.org/model.tar.bz2", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "fixture", roles)
    private fun rejected(bytes: ByteArray, source: SpeechDownload = spec(bytes)) {
        val root = Files.createTempDirectory("publisher-reject").toFile()
        try {
            assertThrows(Exception::class.java) { PublisherSpeechPackage.stage(bytes.inputStream(), source, root) }
            assertTrue("Failed install left staged files", root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun extractsPublisherFilesAndGeneratesVerifiedInternalManifest() {
        val bytes = archive(); val root = Files.createTempDirectory("publisher-good").toFile()
        try {
            val staged = PublisherSpeechPackage.stage(bytes.inputStream(), spec(bytes), root)
            val verified = SpeechModelPackage.verify(staged)
            assertEquals(SpeechProfile.MOONSHINE_TINY_EN, verified.profile)
            assertEquals(roles.keys, org.json.JSONObject(staged.resolve(SpeechModelPackage.MANIFEST).readText()).getJSONObject("roles").keys().asSequence().toSet())
            assertEquals("asset", staged.resolve("encoder_model.ort").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun readsPublisherInputInBlocksInsteadOfOneFileDescriptorReadPerByte() {
        val noise = ByteArray(256 * 1024).also { java.util.Random(7).nextBytes(it) }
        val bytes = archive(defaults.dropLast(1) + (defaults.last().first to noise))
        val delegate = bytes.inputStream()
        var bulkReads = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("Unbuffered read reached the content provider")
            override fun read(b: ByteArray, off: Int, len: Int): Int { bulkReads++; return delegate.read(b, off, len) }
        }
        val root = Files.createTempDirectory("publisher-buffered").toFile()
        try {
            PublisherSpeechPackage.stage(input, spec(bytes), root)
            assertTrue("Too many underlying reads: $bulkReads", bulkReads < 20)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsWrongDigestAndTruncatedArtifact() {
        val bytes = archive(); rejected(bytes, spec(bytes).copy(sha256 = "0".repeat(64)))
        rejected(bytes.copyOf(bytes.size - 12), spec(bytes))
    }
    @Test fun rejectsTraversalForeignRootLinksDuplicatesAndInjectedManifest() {
        rejected(archive(defaults + ("fixture/../escape" to byteArrayOf(1))))
        rejected(archive(defaults + ("another/file" to byteArrayOf(1))))
        rejected(archive(link = true))
        rejected(archive(defaults + defaults.first()))
        rejected(archive(defaults + ("fixture/hearth-speech.json" to "{}".toByteArray())))
    }
    @Test fun rejectsMissingRequiredRoles() { rejected(archive(defaults.dropLast(1))) }
    @Test fun cancelledExtractionLeavesNoSelectablePackage() {
        val bytes = archive(); val root = Files.createTempDirectory("publisher-cancel").toFile()
        try {
            assertThrows(Exception::class.java) {
                PublisherSpeechPackage.stage(bytes.inputStream(), spec(bytes), root) {
                    if (root.walkTopDown().any { it.name == "tokens.txt" && it.length() > 0 }) error("cancelled")
                }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun retryRemovesInterruptedExtractionAndDoesNotInstallTheSameJobTwice() {
        val bytes = archive(); val root = Files.createTempDirectory("publisher-retry").toFile()
        try {
            val interrupted = root.resolve(".staging/publisher-7/package/partial")
            interrupted.parentFile.mkdirs(); interrupted.writeText("incomplete")
            val store = com.sal7one.transiber.caption.LocalSpeechModels(root)
            val installed = store.installPublisher(bytes.inputStream(), spec(bytes), 7)
            assertFalse(root.resolve(".staging/publisher-7").exists())
            assertEquals(installed.id, store.installPublisher(bytes.inputStream(), spec(bytes), 7).id)
            installed.root.resolve("encoder_model.ort").writeText("corrupt")
            assertEquals(installed.id, store.installPublisher(bytes.inputStream(), spec(bytes), 7).id)
            SpeechModelPackage.verify(installed.root)
            assertEquals("asset", installed.root.resolve("encoder_model.ort").readText())
            assertEquals(1, store.list().size)
        } finally { root.deleteRecursively() }
    }

    @Test fun rawNemotronIsVerifiedWithoutCreatingAnIntermediateZip() {
        val bytes = "GGUFtest".toByteArray(); val root = Files.createTempDirectory("publisher-gguf").toFile()
        try {
            val source = spec(bytes).copy(profile = SpeechProfile.NEMOTRON_3_5_ASR_0_6B, archiveRoot = null, roles = mapOf("model" to "model.gguf"))
            val result = PublisherSpeechPackage.stage(bytes.inputStream(), source, root)
            assertEquals(source.profile, SpeechModelPackage.verify(result).profile)
        } finally { root.deleteRecursively() }
    }
    @Test fun omnilingualArchiveIsInstalledAsTwoVerifiedNativeAssets() {
        val entries = listOf("fixture/model.int8.onnx" to "onnx-fixture".toByteArray(), "fixture/tokens.txt" to "a 1\n".toByteArray())
        val bytes = archive(entries)
        val source = spec(bytes).copy(profile = SpeechProfile.OMNILINGUAL_CTC_300M_V2,
            roles = mapOf("model" to "model.int8.onnx", "tokenizer" to "tokens.txt"))
        val root = Files.createTempDirectory("publisher-omnilingual").toFile()
        try {
            val installed = PublisherSpeechPackage.stage(bytes.inputStream(), source, root)
            val verified = SpeechModelPackage.verify(installed)
            assertEquals(SpeechProfile.OMNILINGUAL_CTC_300M_V2, verified.profile)
            assertEquals("a 1\n", installed.resolve("tokens.txt").readText())
        } finally { root.deleteRecursively() }
        rejected(archive(entries.dropLast(1)), source.copy(bytes = archive(entries.dropLast(1)).size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(archive(entries.dropLast(1))).joinToString("") { "%02x".format(it) }))
    }
    @Test fun rejectsOversizedRawArtifact() {
        val bytes = "GGUFtest".toByteArray()
        rejected(bytes, spec(bytes).copy(profile = SpeechProfile.NEMOTRON_3_5_ASR_0_6B, bytes = 4, archiveRoot = null, roles = mapOf("model" to "model.gguf")))
    }
}
