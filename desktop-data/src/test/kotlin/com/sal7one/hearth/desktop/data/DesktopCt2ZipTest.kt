package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.PipelineStage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Tiny synthetic bytes exercise archive integrity and selection only; no model inference or network. */
class DesktopCt2ZipTest {
    private val id = "ct2-fixture-jisr"
    private val transport = "$id.zip"

    @Test fun exactFlatPackageInstallsAndRetainsEveryRuntimePinIncludingManifest() = fixture { directory, root, files ->
        writeZip(root, files.toList())
        val card = card(root, files)
        val library = DesktopModelLibrary(directory, listOf(card))
        library.installArchive(card, root)
        assertFalse(Files.exists(root.resolve(transport)))
        assertEquals(files.keys, Files.list(root).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() })
        val selected = runBlocking { library.verify(id, root) }
        assertEquals("2", selected.options["profile"])
        assertEquals(files.keys.map { "sha256.$it" }.toSet(), selected.options.keys.filter { it.startsWith("sha256.") }.toSet())
        files.forEach { (name, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(name)))
            assertEquals(sha(bytes), selected.options["sha256.$name"])
        }
        // A healthy expanded package is reusable after its transport ZIP was deleted.
        assertEquals(selected, runBlocking { library.download(id) })
    }

    @Test fun missingExtraNestedAndEscapingEntriesNeverPublishAnyComponents() = fixture { directory, root, files ->
        val variants = listOf(
            files.toList().dropLast(1),
            files.toList() + ("shared_vocabulary.txt" to "unverified vocabulary".toByteArray()),
            files.toList() + ("nested/extra.bin" to byteArrayOf(1)),
            files.toList() + ("../outside.bin" to byteArrayOf(1)),
            files.toList() + ("directory/" to byteArrayOf()),
        )
        variants.forEach { entries ->
            writeZip(root, entries)
            val card = card(root, files)
            val library = DesktopModelLibrary(directory, listOf(card))
            assertFails { library.installArchive(card, root) }
            assertTrue(Files.exists(root.resolve(transport)))
            assertFalse(Files.exists(root.resolve("model.bin")))
            assertNoStaging(root)
        }
        assertFalse(Files.exists(root.parent.resolve("outside.bin")))
    }

    @Test fun duplicateComponentsCannotOverwritePreviouslyVerifiedStagingBytes() = fixture { directory, root, files ->
        // Java's ZIP writer disallows duplicate names; equal-width header replacement creates that hostile input.
        val encoded = zip(files.toList() + ("model.bix" to files.getValue("model.bin")))
        Files.write(root.resolve(transport), encoded.toString(Charsets.ISO_8859_1)
            .replace("model.bix", "model.bin").toByteArray(Charsets.ISO_8859_1))
        val card = card(root, files)
        val error = assertFailsWith<IllegalArgumentException> { DesktopModelLibrary(directory, listOf(card)).installArchive(card, root) }
        assertTrue(error.message.orEmpty().contains("Duplicate"))
        assertFalse(Files.exists(root.resolve("model.bin")))
        assertNoStaging(root)
    }

    @Test fun truncatedAndSameSizeChangedComponentsKeepTheOldModelAndArchiveForRepair() = fixture { directory, root, files ->
        val old = "previous model bytes".toByteArray()
        Files.write(root.resolve("model.bin"), old)
        for (bad in listOf(files.getValue("model.bin").dropLast(1).toByteArray(), ByteArray(files.getValue("model.bin").size) { 99 })) {
            writeZip(root, (files + ("model.bin" to bad)).toList())
            val card = card(root, files)
            assertFailsWith<IllegalArgumentException> { DesktopModelLibrary(directory, listOf(card)).installArchive(card, root) }
            assertContentEquals(old, Files.readAllBytes(root.resolve("model.bin")))
            assertTrue(Files.exists(root.resolve(transport)))
            assertNoStaging(root)
        }
        writeZip(root, files.toList())
        val repaired = card(root, files)
        DesktopModelLibrary(directory, listOf(repaired)).installArchive(repaired, root)
        assertContentEquals(files.getValue("model.bin"), Files.readAllBytes(root.resolve("model.bin")))
    }

    @Test fun evenPinnedManifestBytesMustIdentifyTheSelectedModelAndSchema() = fixture { directory, root, files ->
        for (manifest in listOf("""{"schemaVersion":1,"modelId":"another-model"}""",
                """{"schemaVersion":"1","modelId":"$id"}""",
                """{"schemaVersion":1,"modelId":"$id","command":"ignored"}""")) {
            val changed = files + ("hearth-translation.json" to manifest.toByteArray())
            writeZip(root, changed.toList())
            val card = card(root, changed)
            assertFailsWith<IllegalArgumentException> { DesktopModelLibrary(directory, listOf(card)).installArchive(card, root) }
            assertFalse(Files.exists(root.resolve("model.bin")))
            assertNoStaging(root)
        }
    }

    @Test fun cancellationRemovesStagingAndRetryUsesTheRetainedVerifiedArchive() = fixture { directory, root, files ->
        writeZip(root, files.toList())
        val card = card(root, files)
        val library = DesktopModelLibrary(directory, listOf(card))
        var cancel = false
        assertFailsWith<CancellationException> {
            library.installArchive(card, root, { progress -> if (progress.completed > 0) cancel = true }) {
                if (cancel) throw CancellationException("owner paused")
            }
        }
        assertTrue(Files.isRegularFile(root.resolve(transport)))
        assertFalse(Files.exists(root.resolve("model.bin")))
        assertNoStaging(root)
        library.installArchive(card, root)
        assertEquals(7, Files.list(root).use { it.count() }.toInt())
    }

    @Test fun expandedSizeAndCompleteFlatPinsAreMandatoryBeforeAnyExtraction() = fixture { directory, root, files ->
        writeZip(root, files.toList())
        val original = card(root, files)
        val archive = requireNotNull(original.archive)
        for (invalid in listOf(
                archive.copy(expandedBytes = 500_000_001),
                archive.copy(files = archive.files.filter { it.path != "hearth-translation.json" }, byPath = emptyMap()),
                archive.copy(files = archive.files + archive.files.first()),
                archive.copy(root = "unexpected-root"),
            )) {
            val bad = original.copy(archive = invalid)
            assertFailsWith<IllegalArgumentException> { DesktopModelLibrary(directory, listOf(bad)).installArchive(bad, root) }
            assertFalse(Files.exists(root.resolve("model.bin")))
            assertNoStaging(root)
        }
    }

    @Test fun installedVerificationRejectsChangedIdentityAndUnexpectedVocabularyOverrides() = fixture { directory, root, files ->
        writeZip(root, files.toList())
        val card = card(root, files)
        val library = DesktopModelLibrary(directory, listOf(card))
        library.installArchive(card, root)
        runBlocking { library.verify(id, root) }
        Files.writeString(root.resolve("hearth-translation.json"), "x".repeat(files.getValue("hearth-translation.json").size))
        assertFailsWith<IllegalStateException> { runBlocking { library.verify(id, root) } }
        Files.write(root.resolve("hearth-translation.json"), files.getValue("hearth-translation.json"))
        Files.writeString(root.resolve("shared_vocabulary.txt"), "unverified override")
        assertFailsWith<IllegalArgumentException> { runBlocking { library.verify(id, root) } }
    }

    private fun card(root: Path, files: Map<String, ByteArray>): DesktopModelCard {
        val zipped = Files.readAllBytes(root.resolve(transport))
        val pins = files.map { (name, bytes) -> DesktopModelArchiveFile(name, bytes.size.toLong(), sha(bytes)) }
        return DesktopModelCard(id, "Jisr archive integrity fixture", "ct2", PipelineStage.TRANSLATION,
            "marian-local", setOf("en", "ar"), "https://example.invalid/model", "Apache-2.0", "synthetic integrity fixture",
            mapOf("profile" to "2", "backend" to "ct2", "family" to "ct2", "sources" to "en", "targets" to "ar"),
            listOf(DesktopModelArtifact(transport, "https://example.invalid/$transport", zipped.size.toLong(), sha(zipped))),
            archive = DesktopModelArchive("zip", "", pins.sumOf { it.bytes }, pins))
    }
    private fun writeZip(root: Path, entries: List<Pair<String, ByteArray>>) { Files.write(root.resolve(transport), zip(entries)) }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output -> entries.forEach { (name, data) ->
            output.putNextEntry(ZipEntry(name)); output.write(data); output.closeEntry()
        } }
    }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertNoStaging(root: Path) = assertFalse(Files.list(root).use { it.anyMatch { path -> path.fileName.toString().startsWith(".extract-") } })
    private fun fixture(block: (Path, Path, Map<String, ByteArray>) -> Unit) {
        val directory = Files.createTempDirectory("hearth-ct2-zip-")
        try {
            val root = directory.resolve("models/$id"); Files.createDirectories(root)
            val files = linkedMapOf("model.bin" to ByteArray(256 * 1024) { (it % 251).toByte() },
                "config.json" to "{}".toByteArray(), "shared_vocabulary.json" to "[]".toByteArray(),
                "tokenizer.json" to "{}".toByteArray(), "LICENSE" to "license fixture".toByteArray(),
                "NOTICE" to "notice fixture".toByteArray(), "hearth-translation.json" to """{"schemaVersion":1,"modelId":"$id"}""".toByteArray())
            block(directory, root, files)
        } finally { directory.toFile().deleteRecursively() }
    }
}
