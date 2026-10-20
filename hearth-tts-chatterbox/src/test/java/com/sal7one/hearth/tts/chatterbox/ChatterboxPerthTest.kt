package com.sal7one.hearth.tts.chatterbox

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ChatterboxPerthTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(root: File, extra: Boolean = false, compressed: Boolean = false): Pair<File, PerthRecipe> {
        val weight = byteArrayOf(0, 0, 0, 63)
        val bias = byteArrayOf(0, 0, 0, 0)
        val entries = linkedMapOf("archive/data/0" to weight, "archive/data/1" to bias,
            "archive/data.pkl" to "DO NOT EXECUTE ME".toByteArray())
        if (extra) entries["../outside"] = byteArrayOf(1)
        val source = File(root, "source.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            entries.forEach { (name, data) ->
                val entry = ZipEntry(name)
                if (!compressed) { entry.method = ZipEntry.STORED; entry.size = data.size.toLong(); entry.compressedSize = entry.size; entry.crc = CRC32().apply { update(data) }.value }
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
            }
        }
        val output = ByteArrayOutputStream().apply {
            write("CBPERTH1".toByteArray())
            write(ByteBuffer.allocate(7 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                listOf(2,32000,2048,320,2048,128,java.lang.Float.floatToRawIntBits(1e-9f)).forEach { putInt(it) }
            }.array())
            write(ByteBuffer.allocate(5 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(5) { putInt(1) } }.array())
            write(weight); write(bias)
        }.toByteArray()
        val inventory = entries.filterKeys { it != "../outside" }.mapValues { it.value.size.toLong() }
        val recipe = PerthRecipe(source.length(), hash(source.readBytes()), output.size.toLong(), hash(output), inventory,
            listOf(listOf(PerthLayer(1,1,1,1,PerthTensor("archive/data/0",4,hash(weight)),PerthTensor("archive/data/1",4,hash(bias))))))
        return source to recipe
    }

    @Test fun copiesExactTensorBytesAndNeverInterpretsPickle() {
        val root = Files.createTempDirectory("perth-test").toFile()
        try {
            val (source, recipe) = fixture(root); val destination = File(root, "installed/perth.bin")
            convertPerth(source, destination, recipe) {}
            assertEquals(recipe.outputBytes, destination.length())
            assertEquals(recipe.outputSha256, hash(destination.readBytes()))
            assertEquals("DO NOT EXECUTE ME", java.util.zip.ZipFile(source).use { it.getInputStream(it.getEntry("archive/data.pkl")).readBytes().toString(Charsets.UTF_8) })
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptSourceTensorAndUnexpectedZipEntriesNeverReplaceInstalledFile() {
        val root = Files.createTempDirectory("perth-test").toFile()
        try {
            val destination = File(root,"perth.bin").apply { writeText("existing") }
            val (source, recipe) = fixture(root)
            assertThrows(IllegalStateException::class.java) { convertPerth(source,destination,recipe.copy(sourceSha256="0".repeat(64))) {} }
            val layer = recipe.groups.single().single()
            val bad = recipe.copy(groups=listOf(listOf(layer.copy(weight=layer.weight.copy(sha256="0".repeat(64))))))
            assertThrows(IllegalStateException::class.java) { convertPerth(source,destination,bad) {} }
            val (extra, extraRecipe) = fixture(root,extra=true)
            assertThrows(IllegalArgumentException::class.java) { convertPerth(extra,destination,extraRecipe) {} }
            val (compressed, compressedRecipe) = fixture(root,compressed=true)
            assertThrows(IllegalArgumentException::class.java) { convertPerth(compressed,destination,compressedRecipe) {} }
            assertEquals("existing",destination.readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationAndFinalOutputMismatchPreserveInstalledFile() {
        val root = Files.createTempDirectory("perth-test").toFile()
        try {
            val (source,recipe)=fixture(root); val destination=File(root,"perth.bin").apply { writeText("existing") }
            var steps=0
            assertThrows(CancellationException::class.java) { convertPerth(source,destination,recipe) { if (++steps == 11) throw CancellationException("owner cancelled") } }
            assertThrows(IllegalStateException::class.java) { convertPerth(source,destination,recipe.copy(outputSha256="0".repeat(64))) {} }
            assertEquals("existing",destination.readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally { root.deleteRecursively() }
    }
}
