package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.PipelineStage
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class DesktopModelArchiveTest {
    private val tarball = "qwen-test.tar.bz2"
    private val rootName = "model"

    @Test fun installsOnlyPinnedComponentsAndDeletesTheVerifiedArchive() {
        withDirectory { directory ->
            val library = DesktopModelLibrary(directory)
            val root = directory.resolve("models/qwen3-asr-0.6b"); Files.createDirectories(root)
            val frontend = byteArrayOf(1, 2, 3, 4)
            val vocab = "{}".toByteArray()
            archive(root.resolve(tarball), listOf(
                "model/conv_frontend.onnx" to frontend,
                "model/tokenizer/vocab.json" to vocab,
                "model/test_wavs/extra.wav" to ByteArray(8),
            ))
            val files = listOf(pin("conv_frontend.onnx", frontend), pin("tokenizer/vocab.json", vocab))
            library.installArchive(card(files), root)
            assertContentEquals(frontend, Files.readAllBytes(root.resolve("conv_frontend.onnx")))
            assertContentEquals(vocab, Files.readAllBytes(root.resolve("tokenizer/vocab.json")))
            assertFalse(Files.exists(root.resolve("test_wavs/extra.wav")))
            assertFalse(Files.exists(root.resolve(tarball)))
        }
    }

    @Test fun aMissingPinnedComponentFailsAndKeepsTheArchiveForRetry() {
        withDirectory { directory ->
            val library = DesktopModelLibrary(directory)
            val root = directory.resolve("models/qwen3-asr-0.6b"); Files.createDirectories(root)
            val frontend = byteArrayOf(1, 2, 3, 4)
            archive(root.resolve(tarball), listOf("model/conv_frontend.onnx" to frontend))
            val files = listOf(pin("conv_frontend.onnx", frontend), pin("tokenizer/vocab.json", byteArrayOf(9)))
            assertFailsWith<IllegalArgumentException> { library.installArchive(card(files), root) }
            assertTrue(Files.isRegularFile(root.resolve(tarball)), "A failed extraction must keep the verified archive")
        }
    }

    @Test fun aTamperedComponentFailsVerification() {
        withDirectory { directory ->
            val library = DesktopModelLibrary(directory)
            val root = directory.resolve("models/qwen3-asr-0.6b"); Files.createDirectories(root)
            val bytes = byteArrayOf(7, 7, 7)
            archive(root.resolve(tarball), listOf("model/conv_frontend.onnx" to bytes))
            val files = listOf(DesktopModelArchiveFile("conv_frontend.onnx", bytes.size.toLong(), "0".repeat(64)))
            assertFailsWith<IllegalArgumentException> { library.installArchive(card(files), root) }
            assertFalse(Files.exists(root.resolve("conv_frontend.onnx")))
        }
    }

    @Test fun traversalAndSymlinkEntriesAreRejected() {
        withDirectory { directory ->
            val library = DesktopModelLibrary(directory)
            val root = directory.resolve("models/qwen3-asr-0.6b"); Files.createDirectories(root)
            val bytes = byteArrayOf(1)
            val files = listOf(pin("conv_frontend.onnx", bytes))
            archive(root.resolve(tarball), listOf("../escape.onnx" to bytes))
            assertFailsWith<IllegalArgumentException> { library.installArchive(card(files), root) }
            assertFalse(Files.exists(directory.resolve("models/escape.onnx")))
            Files.deleteIfExists(root.resolve(tarball))
            symlinkArchive(root.resolve(tarball), files)
            assertFailsWith<IllegalArgumentException> { library.installArchive(card(files), root) }
        }
    }

    private fun card(files: List<DesktopModelArchiveFile>) = DesktopModelCard(
        id = "qwen3-asr-0.6b", name = "Qwen3-ASR", family = "Qwen3-ASR", stage = PipelineStage.STT,
        engineId = "qwen3-asr-local", languages = setOf("en"), source = "https://example.invalid",
        license = "Apache-2.0", note = "test", options = emptyMap(),
        artifacts = listOf(DesktopModelArtifact(tarball, "https://example.invalid/$tarball", 1L, "0".repeat(64))),
        archive = DesktopModelArchive("tar.bz2", rootName, files.sumOf { it.bytes }, files),
        platforms = setOf("macos-arm64"),
    )

    private fun pin(path: String, bytes: ByteArray) =
        DesktopModelArchiveFile(path, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun archive(target: Path, entries: List<Pair<String, ByteArray>>) = writeArchive(target) { tar ->
        for ((name, bytes) in entries) {
            val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }

    private fun symlinkArchive(target: Path, files: List<DesktopModelArchiveFile>) = writeArchive(target) { tar ->
        val entry = TarArchiveEntry("$rootName/link.onnx", TarConstants.LF_SYMLINK)
        entry.linkName = "/etc/passwd"
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
        for (file in files) {
            val bytes = ByteArray(file.bytes.toInt())
            val real = TarArchiveEntry("$rootName/${file.path}").apply { size = file.bytes }
            tar.putArchiveEntry(real)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }

    private fun writeArchive(target: Path, fill: (TarArchiveOutputStream) -> Unit) {
        BZip2CompressorOutputStream(Files.newOutputStream(target)).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                fill(tar)
            }
        }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("hearth-model-archive-")
        try { block(directory) } finally { directory.toFile().deleteRecursively() }
    }
}
