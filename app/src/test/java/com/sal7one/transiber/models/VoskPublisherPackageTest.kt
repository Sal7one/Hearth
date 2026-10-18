package com.sal7one.transiber.models

import com.sal7one.common_jni.model.ModelIntegrity
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry

class VoskPublisherPackageTest {
    private val files = listOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf", "graph/HCLG.fst")
        .map { "fixture/$it" to "fixture-$it".toByteArray() }

    private fun archive(entries: List<Pair<String, ByteArray>> = files, mode: Int? = null,
                        method: Int = ZipEntry.DEFLATED): ByteArray {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            entries.forEachIndexed { index, (name, bytes) ->
                val entry = ZipArchiveEntry(name).apply {
                    if (index == 0 && mode != null) unixMode = mode
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putArchiveEntry(entry)
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun source(bytes: ByteArray, entries: List<Pair<String, ByteArray>> = files): VoskDownload {
        val fixtureRoot = Files.createTempDirectory("vosk-fixture-tree").toFile()
        val digest = try {
            ModelIntegrity.stageDirectory(fixtureRoot, "fixture") { sink ->
                entries.forEach { (path, content) -> sink.addFile(path.removePrefix("fixture/"), content.inputStream()) }
            }.digest.hex
        } finally { fixtureRoot.deleteRecursively() }
        val original = VoskDownloads.packages.first()
        return original.copy(artifact = original.artifact.copy(url = "https://example.org/fixture.zip", bytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, archiveRoot = "fixture"),
            expandedBytes = entries.sumOf { it.second.size.toLong() }, treeSha256 = digest)
    }

    private fun install(bytes: ByteArray, source: VoskDownload, root: File, checkActive: () -> Unit = {}): File {
        val work = File(root, "work").apply { mkdirs() }
        val models = File(root, "models")
        return ModelIntegrity.stageDirectory(models, "fixture", source.treeSha256) { sink ->
            VoskPublisherPackage.populate(bytes.inputStream(), source, work, sink, checkActive)
        }.file
    }

    private fun rejected(bytes: ByteArray, source: VoskDownload = source(bytes)) {
        val root = Files.createTempDirectory("vosk-rejected").toFile()
        try {
            assertThrows(Exception::class.java) { install(bytes, source, root) }
            assertTrue("Rejected model was published", root.resolve("models").listFiles().orEmpty().isEmpty())
            assertTrue("Private ZIP was retained", root.resolve("work").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun installsOnlyAfterArchiveAndExpandedTreePinsMatch() {
        val bytes = archive(); val source = source(bytes)
        val root = Files.createTempDirectory("vosk-good").toFile()
        try {
            val installed = install(bytes, source, root)
            assertEquals("fixture-am/final.mdl", installed.resolve("am/final.mdl").readText())
            assertEquals(source.treeSha256, ModelIntegrity.inspect(installed).digest.hex)
            assertTrue(root.resolve("work").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun storedEntriesUseTheSameBoundedVerifiedPath() {
        val bytes = archive(method = ZipEntry.STORED)
        val root = Files.createTempDirectory("vosk-stored").toFile()
        try {
            val source = source(bytes)
            assertEquals(source.treeSha256, ModelIntegrity.inspect(install(bytes, source, root)).digest.hex)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsUnsupportedCompressionEvenWithMatchingArchiveAndTreePins() {
        val bytes = archive()
        // Re-label every local and central entry as BZIP2. Its archive digest
        // is pinned below, so format rejection cannot rely on a digest mismatch.
        for (i in 0 until bytes.size - 12) {
            if (bytes[i] != 0x50.toByte() || bytes[i + 1] != 0x4b.toByte()) continue
            val offset = when {
                bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte() -> i + 8
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte() -> i + 10
                else -> continue
            }
            bytes[offset] = 12; bytes[offset + 1] = 0
        }
        rejected(bytes)
    }

    @Test fun rejectsWrongArchiveDigestSizeAndExpandedTreePin() {
        val bytes = archive(); val source = source(bytes)
        rejected(bytes, source.copy(artifact = source.artifact.copy(sha256 = "0".repeat(64))))
        rejected(bytes.copyOf(bytes.size - 8), source)
        rejected(bytes, source.copy(artifact = source.artifact.copy(bytes = bytes.size + 1L)))
        rejected(bytes, source.copy(treeSha256 = "0".repeat(64)))
    }

    @Test fun rejectsTraversalForeignRootDuplicateAndInvalidSeparatorPaths() {
        listOf("fixture/../escape", "another/file", "fixture/./file", "fixture/a\\b", "fixture/a:b", "fixture//file")
            .forEach { path -> rejected(archive(files + (path to byteArrayOf(1)))) }
        rejected(archive(files + files.first()))
    }

    @Test fun rejectsLinksAndSpecialFileTypesFromCentralDirectory() {
        rejected(archive(mode = 0xa1ff)) // Symbolic link.
        rejected(archive(mode = 0x11ff)) // FIFO.
        rejected(archive(mode = 0x41ed)) // Directory mode with a regular-file path.
    }

    @Test fun rejectsExpandedSizeMismatchAndMissingRequiredAssets() {
        val bytes = archive(); val source = source(bytes)
        rejected(bytes, source.copy(expandedBytes = source.expandedBytes - 1))
        rejected(bytes, source.copy(expandedBytes = source.expandedBytes + 1))
        val missing = files.dropLast(1)
        val incomplete = archive(missing)
        rejected(incomplete, source(incomplete, missing))
    }

    @Test fun rejectsDamagedCentralDirectoryCrcEvenWithMatchingArchiveDigest() {
        val bytes = archive()
        val central = (0 until bytes.size - 4).first { i ->
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
        }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        rejected(bytes)
    }

    @Test fun cancellationDuringExtractionPublishesNothingAndRemovesPrivateZip() {
        val bytes = archive(); val source = source(bytes)
        val root = Files.createTempDirectory("vosk-cancelled").toFile()
        try {
            val failure = assertThrows(Exception::class.java) {
                install(bytes, source, root) {
                    if (root.resolve("models").walkTopDown().any { it.name == "final.mdl" && it.length() > 0 }) error("cancelled")
                }
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message == "cancelled" })
            assertTrue(root.resolve("models").listFiles().orEmpty().isEmpty())
            assertTrue(root.resolve("work").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    /** Explicit opt-in integration coverage; model weights never belong in the test tree. */
    @Test fun suppliedOfficialArchivesMatchBothPinsThroughTheProductionInstaller() {
        val directory = System.getenv("HEARTH_VOSK_ARCHIVES")?.let(::File)
        org.junit.Assume.assumeTrue("Set HEARTH_VOSK_ARCHIVES to locally downloaded official ZIPs", directory?.isDirectory == true)
        VoskDownloads.packages.forEach { source ->
            val archive = File(checkNotNull(directory), source.artifact.fileName)
            assertTrue("Missing ${archive.name}", archive.isFile)
            val root = Files.createTempDirectory("vosk-official").toFile()
            try {
                val work = File(root, "work").apply { mkdirs() }
                val staged = ModelIntegrity.stageDirectory(File(root, "models"), "model", source.treeSha256) { sink ->
                    archive.inputStream().use { VoskPublisherPackage.populate(it, source, work, sink) }
                }
                assertEquals(source.expandedBytes, staged.inspection.sizeBytes)
                assertEquals(source.treeSha256, ModelIntegrity.inspect(staged.file).digest.hex)
                assertTrue(work.listFiles().orEmpty().isEmpty())
            } finally { root.deleteRecursively() }
        }
    }
}
