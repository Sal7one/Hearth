package com.sal7one.hearth.tts.chatterbox

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Converts only the pinned official Perth ZIP. data.pkl is never read or executed. */
object ChatterboxPerth {
    const val FILENAME = "perth-encoder.bin"
    const val SOURCE_URL = ChatterboxPerthRecipe.SOURCE_URL
    const val SOURCE_BYTES = ChatterboxPerthRecipe.SOURCE_BYTES
    const val SOURCE_SHA256 = ChatterboxPerthRecipe.SOURCE_SHA256
    const val OUTPUT_BYTES = ChatterboxPerthRecipe.OUTPUT_BYTES
    const val OUTPUT_SHA256 = ChatterboxPerthRecipe.OUTPUT_SHA256

    fun ready(root: File): Boolean = File(root, FILENAME).let { it.isFile && it.length() == OUTPUT_BYTES }

    fun verify(root: File, cancelled: () -> Unit = {}) {
        verifyFile(File(root, FILENAME), OUTPUT_BYTES, OUTPUT_SHA256, cancelled)
    }

    /** Call from a worker thread. Publication is atomic; cancellation preserves the installed file. */
    fun install(source: File, root: File, cancelled: () -> Unit = {}) {
        convertPerth(source, File(root, FILENAME), ChatterboxPerthRecipe.value, cancelled)
    }
}

internal data class PerthTensor(val name: String, val bytes: Long, val sha256: String)
internal data class PerthLayer(val outChannels: Int, val inChannels: Int, val kernel: Int,
    val activation: Int, val weight: PerthTensor, val bias: PerthTensor)
internal data class PerthRecipe(val sourceBytes: Long, val sourceSha256: String,
    val outputBytes: Long, val outputSha256: String, val entries: Map<String, Long>,
    val groups: List<List<PerthLayer>>)

internal fun convertPerth(source: File, destination: File, recipe: PerthRecipe, cancelled: () -> Unit) {
    require(source.canonicalFile != destination.canonicalFile) { "Perth source and output must differ" }
    require(recipe.sourceBytes in 1..40_000_000 && recipe.outputBytes in 1..40_000_000) { "Invalid Perth recipe size" }
    verifyFile(source, recipe.sourceBytes, recipe.sourceSha256, cancelled)
    check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create Perth model directory" }
    val temporary = File.createTempFile("perth-", ".part", destination.parentFile)
    try {
        ZipFile(source).use { archive ->
            val actual = linkedMapOf<String, Long>()
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) {
                cancelled()
                val entry = enumeration.nextElement()
                require(actual.size < recipe.entries.size) { "Unexpected Perth ZIP entry" }
                require(!entry.isDirectory && entry.method == ZipEntry.STORED && entry.compressedSize == entry.size) { "Invalid Perth ZIP entry type" }
                require(entry.name in recipe.entries && actual.put(entry.name, entry.size) == null) { "Unexpected or duplicate Perth ZIP entry" }
                require(entry.size == recipe.entries[entry.name]) { "Perth ZIP entry size mismatch" }
            }
            require(actual == recipe.entries) { "Perth ZIP inventory mismatch" }
            temporary.outputStream().buffered().use { output ->
                output.write("CBPERTH1".toByteArray(Charsets.US_ASCII))
                listOf(2, 32000, 2048, 320, 2048, 128).forEach { output.i32(it) }
                output.i32(java.lang.Float.floatToRawIntBits(1e-9f))
                recipe.groups.forEach { layers ->
                    cancelled(); output.i32(layers.size)
                    layers.forEach { layer ->
                        require(layer.outChannels in 1..256 && layer.inChannels in 1..256 && layer.kernel in 1..7 && layer.activation in 0..1) { "Invalid Perth layer" }
                        require(layer.weight.bytes == layer.outChannels.toLong() * layer.inChannels * layer.kernel * 4 && layer.bias.bytes == layer.outChannels.toLong() * 4) { "Invalid Perth tensor size" }
                        output.i32(layer.outChannels); output.i32(layer.inChannels); output.i32(layer.kernel); output.i32(layer.activation)
                        listOf(layer.weight, layer.bias).forEach { tensor ->
                            require(recipe.entries[tensor.name] == tensor.bytes) { "Perth tensor absent from inventory" }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            archive.getInputStream(archive.getEntry(tensor.name)).use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    cancelled(); val n = input.read(buffer); if (n < 0) break
                                    count += n; require(count <= tensor.bytes) { "Perth tensor exceeds pinned size" }
                                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                                }
                            }
                            check(count == tensor.bytes && digest.hex() == tensor.sha256) { "Perth tensor checksum failed: ${tensor.name}" }
                        }
                    }
                }
            }
        }
        verifyFile(temporary, recipe.outputBytes, recipe.outputSha256, cancelled)
        cancelled()
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

private fun OutputStream.i32(value: Int) {
    write(value and 255); write((value ushr 8) and 255); write((value ushr 16) and 255); write((value ushr 24) and 255)
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

private fun verifyFile(file: File, bytes: Long, sha256: String, cancelled: () -> Unit) {
    cancelled()
    check(file.isFile && file.length() == bytes) { "Perth file size mismatch: ${file.name}" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { cancelled(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    check(digest.hex() == sha256) { "Perth file checksum failed: ${file.name}" }
}
