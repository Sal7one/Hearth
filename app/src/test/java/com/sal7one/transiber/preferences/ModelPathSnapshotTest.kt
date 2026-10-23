package com.sal7one.transiber.preferences

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ModelPathSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun systemAliasForThePrivateRootAcceptsCanonicalInstallerPaths() {
        // Android exposes /data/user/0 while installers publish canonical /data/data paths.
        val actual = temporary.newFolder("data-data").canonicalFile
        val alias = File(temporary.root,"data-user-0")
        Files.createSymbolicLink(alias.toPath(),actual.toPath())
        val installed = File(actual,"models/whisper.bin").apply {parentFile!!.mkdirs();writeText("pinned model")}
        val canonical = ModelPathSnapshots.capture(alias,installed,installed.length())
        val lexical = ModelPathSnapshots.capture(alias,File(alias,"models/whisper.bin"),installed.length())
        assertTrue(ModelPathSnapshots.check(alias,canonical).available)
        assertTrue(ModelPathSnapshots.check(alias,lexical).available)
        // An extra link inside that root is still forbidden, even when it points to a valid model.
        val nested = File(actual,"models/alias.bin")
        Files.createSymbolicLink(nested.toPath(),installed.toPath())
        assertThrows(IllegalArgumentException::class.java) {ModelPathSnapshots.capture(alias,nested)}
    }
    @Test fun availabilityChecksMissingSizeAndModificationsWithoutHashing() {
        val root = temporary.newFolder("private")
        val file = File(root, "models/test.gguf").apply { parentFile!!.mkdirs(); writeText("model") }
        val binding = ModelPathSnapshots.capture(root, file, expectedBytes = 5)
        assertTrue(ModelPathSnapshots.check(root, binding).available)
        assertTrue(file.setLastModified(file.lastModified() + 2000))
        val changed = ModelPathSnapshots.check(root, binding)
        assertTrue(changed.available)
        assertTrue(changed.changed)
        file.appendText("changed")
        assertFalse(ModelPathSnapshots.check(root, binding).available)
        assertTrue(ModelPathSnapshots.check(root, binding).error.orEmpty().contains("size changed"))
        file.delete()
        assertFalse(ModelPathSnapshots.check(root, binding).available)
    }
    @Test fun directoryCapturesEveryModelFileAndReportsMissingNestedAsset() {
        val root = temporary.newFolder("private")
        val model = File(root, "speech-models/model").apply { mkdirs() }
        File(model, "encoder.bin").writeText("encoder")
        val nested = File(model, "tokenizer/vocab.txt").apply { parentFile!!.mkdirs(); writeText("vocab") }
        val binding = ModelPathSnapshots.capture(root, model)
        assertEquals(2, binding.files.size)
        assertEquals(12L, binding.bytes)
        assertTrue(ModelPathSnapshots.check(root, binding).available)
        nested.delete()
        assertFalse(ModelPathSnapshots.check(root, binding).available)
    }
    @Test fun bindingsRejectOutsidePathsLinksAndManifestTraversal() {
        val root = temporary.newFolder("private")
        val outside = temporary.newFile("external-model")
        assertThrows(IllegalArgumentException::class.java) { ModelPathSnapshots.capture(root, outside) }
        val model = File(root, "models").apply { mkdirs() }
        val link = File(model, "linked.bin")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { ModelPathSnapshots.capture(root, model) }
        assertFalse(ModelPathSnapshots.check(root, BoundModelPath(model.path, true, 1,
            listOf(BoundModelFile("../../external-model", 1, 0)))).available)
    }
    @Test fun selectedMissingPathsRemainRepairableAndSizeMismatchCannotBeBlessedAtLaunch() {
        val root = temporary.newFolder("private")
        val missing = File(root, "models/selected.gguf")
        val binding = ModelPathSnapshots.capture(root, missing, expectedBytes = 123, directory = false)
        assertFalse(ModelPathSnapshots.check(root, binding).available)
        missing.parentFile!!.mkdirs(); missing.writeText("short")
        assertThrows(IllegalArgumentException::class.java) { ModelPathSnapshots.capture(root, missing, 123) }
        assertFalse(ModelPathSnapshots.check(root, binding).available)
    }
    @Test fun startupCaptureFailureKeepsItsActualExpectedAndObservedSizeCause() {
        val root = temporary.newFolder("private")
        val model = File(root, "selected.gguf").apply { writeText("short") }
        val failure = assertThrows(IllegalArgumentException::class.java) { ModelPathSnapshots.capture(root, model, 123) }
        val saved = BoundModelPath(model.path, false, 123, emptyList(), failure.message)
        val result = ModelPathSnapshots.check(root, saved)
        assertFalse(result.available)
        assertTrue(result.error.orEmpty().contains("expected 123, found 5"))
    }
}
