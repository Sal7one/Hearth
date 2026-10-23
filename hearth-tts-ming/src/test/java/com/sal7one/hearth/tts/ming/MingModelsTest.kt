package com.sal7one.hearth.tts.ming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MingModelsTest {
    @Test fun readinessTracksEveryPinnedComponentBeforeAnyHashing() {
        val directory = MingTestSupport.tempDirectory("ming-models-")
        try {
            val assets = MingTestSupport.bundle(directory)
            val models = MingModels(directory, assets)
            assertTrue(models.ready())
            assertTrue(models.missing().isEmpty())

            File(directory, "tokenizer.json").delete()
            assertFalse(models.ready())
            assertEquals(listOf("tokenizer.json"), models.missing())

            File(directory, "part-000.bin").writeBytes(ByteArray(2))
            assertFalse(models.ready())
            assertEquals(listOf("tokenizer.json", "part-000.bin"), models.missing())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun verifyHashesTheBundleAndLinksTheRuntimePartsLayout() {
        val directory = MingTestSupport.tempDirectory("ming-verify-")
        try {
            val assets = MingTestSupport.bundle(directory)
            val models = MingModels(directory, assets)
            models.verify()
            val linked = File(directory, "parts/000.bin")
            assertTrue(Files.isRegularFile(linked.toPath()))
            assertEquals(File(directory, "part-000.bin").readBytes().toList(), linked.readBytes().toList())
            // Idempotent: a second prepare keeps the valid link and does not copy 850 MB.
            models.verify()
            assertEquals(9L, linked.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aReplacedFlatPartIsRelinkedInsteadOfReusingTheStaleInode() {
        val directory = MingTestSupport.tempDirectory("ming-relink-")
        try {
            val assets = MingTestSupport.bundle(directory)
            val models = MingModels(directory, assets)
            models.verify()
            val flat = File(directory, "part-000.bin")
            val linked = File(directory, "parts/000.bin")
            assertEquals(flat.readBytes().toList(), linked.readBytes().toList())

            // A model reinstall can replace the flat file with a new inode of the same length.
            val replacement = "new-partx".toByteArray()
            assertEquals(flat.length(), replacement.size.toLong())
            assertTrue(flat.delete())
            flat.writeBytes(replacement)
            assertFalse(Files.isSameFile(flat.toPath(), linked.toPath()))
            models.prepare()
            assertTrue(Files.isSameFile(flat.toPath(), linked.toPath()))
            assertEquals(replacement.toList(), linked.readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun anyChangedByteOrSizeBlocksTheNativeLoad() {
        val directory = MingTestSupport.tempDirectory("ming-corrupt-")
        try {
            val assets = MingTestSupport.bundle(directory)
            val file = File(directory, "part-000.bin")
            val original = file.readBytes()
            file.writeBytes(original.copyOf().also { it[0] = (it[0] + 1).toByte() })
            val corrupt = File(directory, "parts/000.bin")
            if (corrupt.exists()) corrupt.delete()
            val models = MingModels(directory, assets)
            val error = assertThrows(IllegalStateException::class.java) { models.verify() }
            assertTrue(error.message.orEmpty().contains("checksum failed"))

            file.writeBytes(original + byteArrayOf(0))
            assertThrows(IllegalStateException::class.java) { models.verify() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationStopsBeforeTheBundleIsDeclaredUsable() {
        val directory = MingTestSupport.tempDirectory("ming-cancel-")
        try {
            val models = MingModels(directory, MingTestSupport.bundle(directory))
            assertThrows(IllegalStateException::class.java) {
                models.verify { throw IllegalStateException("cancelled") }
            }
            assertFalse(File(directory, "parts/000.bin").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingDirectoriesAndEmptyCatalogsReportARealReason() {
        val directory = MingTestSupport.tempDirectory("ming-missing-")
        try {
            val absent = File(directory, "absent")
            assertFalse(MingModels(absent).ready())
            assertThrows(IllegalStateException::class.java) { MingModels(absent).verify() }
            assertEquals(17, MingModels(absent).missing().size)
            assertThrows(IllegalArgumentException::class.java) {
                MingModels(directory, emptyList()).prepare()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
