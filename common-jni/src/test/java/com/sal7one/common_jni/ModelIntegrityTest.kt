package com.sal7one.common_jni

import com.sal7one.common_jni.model.ModelDigestKind
import com.sal7one.common_jni.model.ModelDigestTrust
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.model.ModelIntegrityException
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelIntegrityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fileDigestSupportsPinnedAndTofuTrust() {
        val model = temporaryFolder.newFile("model.bin").apply { writeText("abc") }

        val tofu = ModelIntegrity.inspect(model)
        val pinned = ModelIntegrity.inspect(
            model,
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        )

        assertEquals(ModelDigestKind.FILE, tofu.digest.kind)
        assertEquals(ModelDigestTrust.TOFU, tofu.digest.trust)
        assertEquals(ModelDigestTrust.PINNED, pinned.digest.trust)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", pinned.digest.hex)
    }

    @Test
    fun pinnedMismatchNeverPublishesPartialFile() {
        val root = temporaryFolder.newFolder("private")

        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.stageFile(
                ByteArrayInputStream("model".toByteArray()),
                root,
                "model.bin",
                "0".repeat(64)
            )
        }

        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun stagingDoesNotOverwriteAnExistingDestination() {
        val root = temporaryFolder.newFolder("private")
        val existing = root.resolve("model.bin").apply { writeText("old") }

        val staged = ModelIntegrity.stageFile(
            ByteArrayInputStream("new".toByteArray()),
            root,
            "model.bin"
        )

        assertEquals("old", existing.readText())
        assertNotEquals(existing.canonicalPath, staged.file.canonicalPath)
        assertEquals("new", staged.file.readText())
    }

    @Test
    fun stageFileRejectsEmptyAndOversizedInputs() {
        val root = temporaryFolder.newFolder("private")

        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.stageFile(ByteArrayInputStream(byteArrayOf()), root, "empty.bin")
        }
        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.stageFile(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                root,
                "large.bin",
                maxBytes = 3
            )
        }
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun directoryDigestMatchesSharedProtocolVector() {
        val source = temporaryFolder.newFolder("source")
        source.resolve("graph").mkdir()
        source.resolve("am").mkdir()
        source.resolve("graph/b.txt").writeText("bc")
        source.resolve("am/a.bin").writeText("a")

        val inspection = ModelIntegrity.inspect(source)

        assertEquals(ModelDigestKind.TREE, inspection.digest.kind)
        assertEquals(3L, inspection.sizeBytes)
        assertEquals(2, inspection.fileCount)
        assertEquals(
            "a6cfaf59b04050d1745b7088377985e3ada086b49b5528a199129ac6da124e36",
            inspection.digest.hex
        )
    }

    @Test
    fun directoryDigestIsIndependentOfInsertionOrder() {
        val root = temporaryFolder.newFolder("private")
        val first = ModelIntegrity.stageDirectory(root, "first") { sink ->
            sink.addDirectory("graph")
            sink.addFile("graph/b.txt", ByteArrayInputStream("bc".toByteArray()))
            sink.addDirectory("am")
            sink.addFile("am/a.bin", ByteArrayInputStream("a".toByteArray()))
        }
        val second = ModelIntegrity.stageDirectory(root, "second") { sink ->
            sink.addDirectory("am")
            sink.addFile("am/a.bin", ByteArrayInputStream("a".toByteArray()))
            sink.addDirectory("graph")
            sink.addFile("graph/b.txt", ByteArrayInputStream("bc".toByteArray()))
        }

        assertEquals(first.digest.hex, second.digest.hex)
    }

    @Test
    fun directoryRejectsEmptyAndZeroByteOnlyTrees() {
        val root = temporaryFolder.newFolder("private")

        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.stageDirectory(root, "empty") { }
        }
        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.stageDirectory(root, "zero") { sink ->
                sink.addFile("empty.bin", ByteArrayInputStream(byteArrayOf()))
            }
        }
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun directorySinkRejectsTraversalDepthAndLongUtf8Paths() {
        val root = temporaryFolder.newFolder("private")
        val tooDeep = (1..33).joinToString("/") { "d$it" }
        val tooLong = (1..17).joinToString("/") { "x".repeat(250) }

        listOf("../escape", "safe/../../escape", tooDeep, tooLong).forEachIndexed { index, path ->
            assertThrows(ModelIntegrityException::class.java) {
                ModelIntegrity.stageDirectory(root, "tree-$index") { sink ->
                    sink.addFile(path, ByteArrayInputStream(byteArrayOf(1)))
                }
            }
        }
        assertFalse(temporaryFolder.root.resolve("escape").exists())
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun safeNamesRejectUnpairedUtf16Surrogates() {
        assertEquals("model-😀.bin", ModelIntegrity.requireSafeName("model-😀.bin"))
        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.requireSafeName("model-\uD800.bin")
        }
        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.requireSafeName("model-\uDC00.bin")
        }
    }

    @Test
    fun inspectRejectsTopLevelAndNestedSymbolicLinks() {
        val realFile = temporaryFolder.newFile("real.bin").apply { writeText("model") }
        val link = temporaryFolder.root.resolve("link.bin")
        val tree = temporaryFolder.newFolder("tree")
        tree.resolve("data.bin").writeText("data")
        val nestedLink = tree.resolve("nested-link.bin")
        try {
            Files.createSymbolicLink(link.toPath(), realFile.toPath())
            Files.createSymbolicLink(nestedLink.toPath(), realFile.toPath())
        } catch (e: Exception) {
            assumeNoException(e)
        }

        assertThrows(ModelIntegrityException::class.java) { ModelIntegrity.inspect(link) }
        assertThrows(ModelIntegrityException::class.java) { ModelIntegrity.inspect(tree) }
    }

    @Test
    fun tofuVerificationDetectsSameSizeReplacement() {
        val model = temporaryFolder.newFile("model.bin").apply { writeText("first") }
        val learned = ModelIntegrity.inspect(model).digest
        model.writeText("other")

        assertThrows(ModelIntegrityException::class.java) {
            ModelIntegrity.verify(model, learned)
        }
    }
}
