package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MingLocalModelTest {
    @Test
    fun languageAcceptsLocaleTagsAndRejectsOthers() {
        assertEquals("en", MingLocalModel.language("en-US"))
        assertEquals("zh", MingLocalModel.language("zh-Hans"))
        assertEquals("en", MingLocalModel.language("en"))
        assertNull(MingLocalModel.language("ar"))
        assertNull(MingLocalModel.language(""))
    }

    @Test
    fun prepareRelinksAFlatPartThatWasReplaced() {
        val root = bundle()
        try {
            MingLocalModel.prepare(root)
            val source = root.resolve("part-000.bin")
            val target = root.resolve("parts/000.bin")
            assertTrue(Files.isSameFile(source, target))

            // Model downloads replace files with a new inode; they do not overwrite the hardlink inode.
            val replacement = root.resolve("replacement.tmp")
            Files.writeString(replacement, "replacement")
            Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING)
            assertEquals("old", Files.readString(target), "the stale link must still expose the old inode")
            MingLocalModel.prepare(root)
            assertTrue(Files.isSameFile(source, target), "prepare must relink the replaced part")
            assertEquals("replacement", Files.readString(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun prepareRepairsABrokenRelativePartsSymlink() {
        val root = bundle()
        try {
            Files.createDirectories(root.resolve("parts"))
            Files.createSymbolicLink(root.resolve("parts/000.bin"), Path.of("part-000.bin"))
            MingLocalModel.prepare(root)
            assertTrue(Files.isRegularFile(root.resolve("parts/000.bin")))
            assertEquals("old", Files.readString(root.resolve("parts/000.bin")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun verifyRejectsAStalePin() {
        val root = bundle()
        try {
            val selection = EngineSelection(
                "ming-local", "ming-3.0-tts-0.5b-web", root.toString(),
                options = mapOf("sha256.part-000.bin" to "0".repeat(64)),
            )
            assertFailsWith<IllegalStateException> { MingLocalModel.verify(selection) }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun bundle(): Path {
        val root = Files.createTempDirectory("hearth-ming-model-")
        Files.writeString(root.resolve("manifest.json"), "{}")
        Files.writeString(root.resolve("tokenizer.json"), "{}")
        for (index in 0..14) Files.writeString(root.resolve("part-%03d.bin".format(index)), "old")
        return root
    }
}
