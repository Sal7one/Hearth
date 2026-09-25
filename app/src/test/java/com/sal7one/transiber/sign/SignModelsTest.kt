package com.sal7one.transiber.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Install/discovery logic against a temp directory and an in-memory store
 * — no Android framework types, mirroring the caption/ocr test style.
 */
class SignModelsTest {

    private class MemoryStore(root: File) : SignModelStore {
        override val rootDir: File = root
        var selected: String? = null
        override fun readSelectedClassifierId(): String? = selected
        override fun writeSelectedClassifierId(id: String?) {
            selected = id
        }
    }

    private val modelBytes = "fake onnx weights".toByteArray()
    private val labelsJson = """["A", "B", "ئ"]"""

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun inStore(block: (SignModels, MemoryStore, File) -> Unit) {
        val root = Files.createTempDirectory("sign-models").toFile()
        val store = MemoryStore(root)
        try {
            block(SignModels(store), store, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun SignModels.installModel(
        fileName: String = "asl_classifier.onnx",
        expected: String? = null,
    ): SignClassifierInstall = installClassifierFromImport(modelBytes.inputStream(), fileName, expected)

    private fun SignModels.installLabels(
        fileName: String = "asl_classifier_labels.json",
        content: String = labelsJson,
        expected: String? = null,
    ): SignClassifierInstall = installClassifierFromImport(content.byteInputStream(), fileName, expected)

    private fun filesIn(root: File): List<File> =
        root.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun emptyStoreHasNoHandsAndNoClassifiers() = inStore { models, _, _ ->
        assertNull(models.handModelDir())
        assertTrue(models.availableClassifiers().isEmpty())
        assertNull(models.selectedClassifier())
    }

    @Test
    fun classifierNeedsBothModelAndLabelsBeforeDiscoveryListsIt() = inStore { models, _, _ ->
        val modelInstall = models.installModel()
        assertEquals(SignLanguage.ASL_ENGLISH, modelInstall.language)
        assertFalse(modelInstall.complete)
        assertEquals("asl_classifier_labels.json", modelInstall.missingSisterFile)
        assertTrue(models.availableClassifiers().isEmpty())

        val labelsInstall = models.installLabels()
        assertTrue(labelsInstall.complete)
        assertNull(labelsInstall.missingSisterFile)

        val classifiers = models.availableClassifiers()
        assertEquals(1, classifiers.size)
        val info = classifiers.single()
        assertEquals("asl_classifier", info.id)
        assertEquals(SignLanguage.ASL_ENGLISH, info.language)
        assertEquals(File(info.file.parentFile, "asl_classifier.onnx"), info.file)
        assertEquals(File(info.labels.parentFile, "asl_classifier_labels.json"), info.labels)
        assertTrue(info.file.isFile)
        assertTrue(info.labels.isFile)
    }

    @Test
    fun labelsInstalledFirstAlsoReportTheModelAsMissing() = inStore { models, _, _ ->
        val labelsInstall = models.installLabels()
        assertFalse(labelsInstall.complete)
        assertEquals("asl_classifier.onnx", labelsInstall.missingSisterFile)
        assertTrue(models.installModel().complete)
        assertEquals(1, models.availableClassifiers().size)
    }

    @Test
    fun arslClassifierRoutesToTheArabicLanguage() = inStore { models, _, _ ->
        models.installModel("arsl_classifier.onnx")
        models.installLabels("arsl_classifier_labels.json")
        val info = models.availableClassifiers().single()
        assertEquals("arsl_classifier", info.id)
        assertEquals(SignLanguage.ARABIC, info.language)
    }

    @Test
    fun unknownOrUnsafeNamesFailClosedAndInstallNothing() = inStore { models, _, root ->
        for (name in listOf(
            "evil.onnx",
            "../asl_classifier.onnx",
            "subdir/asl_classifier.onnx",
            "asl_classifier.txt",
            ".hidden.onnx",
            "hand_detector.onnx.txt",
        )) {
            assertThrows(Exception::class.java) {
                models.installClassifierFromImport(modelBytes.inputStream(), name)
            }
        }
        assertTrue(filesIn(root).isEmpty())
    }

    @Test
    fun digestMismatchFailsClosedAndLeavesNoResidue() = inStore { models, _, root ->
        val wrongDigest = sha256("other bytes".toByteArray())
        assertThrows(IllegalStateException::class.java) {
            models.installModel(expected = wrongDigest)
        }
        assertTrue(filesIn(root).none { it.name.endsWith(".part") || it.name.endsWith(".onnx") })
    }

    @Test
    fun matchingDigestInstalls() = inStore { models, _, _ ->
        val install = models.installModel(expected = sha256(modelBytes))
        assertTrue(install.installedFile.isFile)
        assertEquals(modelBytes.size.toLong(), install.installedFile.length())
    }

    @Test
    fun emptyImportFailsClosed() = inStore { models, _, root ->
        assertThrows(IllegalStateException::class.java) {
            models.installClassifierFromImport(ByteArray(0).inputStream(), "asl_classifier.onnx")
        }
        assertTrue(filesIn(root).none())
    }

    @Test
    fun invalidLabelsJsonNeverPublishes() = inStore { models, _, root ->
        assertThrows(IllegalStateException::class.java) {
            models.installLabels(content = "not json at all")
        }
        assertThrows(IllegalStateException::class.java) {
            models.installLabels(content = """["A", 3]""")
        }
        assertTrue(filesIn(root).none())
    }

    @Test
    fun selectionFailsClosedAndPersists() = inStore { models, store, _ ->
        models.installModel()
        assertThrows(IllegalStateException::class.java) {
            models.selectClassifier("asl_classifier")
        }
        assertNull(store.selected)

        models.installLabels()
        val selected = models.selectClassifier("asl_classifier")
        assertEquals("asl_classifier", selected.id)
        assertEquals("asl_classifier", store.selected)
        assertEquals(selected, models.selectedClassifier())

        assertThrows(IllegalStateException::class.java) {
            models.selectClassifier("arsl_classifier")
        }

        models.clearSelectedClassifier()
        assertNull(store.selected)
        assertNull(models.selectedClassifier())
    }

    @Test
    fun staleSelectionDisappearsWhenTheFilesDo() = inStore { models, store, root ->
        models.installModel()
        models.installLabels()
        models.selectClassifier("asl_classifier")
        File(root, "classifiers/asl_classifier.onnx").delete()
        assertEquals("asl_classifier", store.selected) // store keeps the id…
        assertNull(models.selectedClassifier()) // …but it no longer resolves
    }

    @Test
    fun handModelDirRequiresBothHandFiles() = inStore { models, _, _ ->
        models.installHandModelsFromImport(modelBytes.inputStream(), "hand_detector.onnx")
        assertNull(models.handModelDir())

        val landmarks = models.installHandModelsFromImport(
            modelBytes.inputStream(),
            "hand_landmarks_detector.onnx",
            sha256(modelBytes),
        )
        val hands = models.handModelDir()
        assertNotNull(hands)
        assertTrue(File(hands!!, "hand_detector.onnx").isFile)
        assertTrue(File(hands, "hand_landmarks_detector.onnx").isFile)
        assertEquals(landmarks.name, "hand_landmarks_detector.onnx")
    }

    @Test
    fun handModelImportRejectsWrongNames() = inStore { models, _, root ->
        assertThrows(IllegalArgumentException::class.java) {
            models.installHandModelsFromImport(modelBytes.inputStream(), "hand_landmarker.onnx")
        }
        assertThrows(IllegalArgumentException::class.java) {
            models.installHandModelsFromImport(modelBytes.inputStream(), "asl_classifier.onnx")
        }
        assertTrue(filesIn(root).none())
    }

    @Test
    fun reimportReplacesTheFileAtomically() = inStore { models, _, _ ->
        models.installModel()
        val newer = "second fake onnx weights".toByteArray()
        models.installClassifierFromImport(newer.inputStream(), "asl_classifier.onnx")
        models.installLabels()
        val info = models.availableClassifiers().single()
        assertEquals(newer.size.toLong(), info.file.length())
    }
}
