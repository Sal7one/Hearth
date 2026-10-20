package com.sal7one.common_jni.translation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class Ct2ArchiveTest {
    private val content="test model".toByteArray()
    private val hash=MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val spec=Ct2Catalog.models.first().copy(files=listOf(Ct2File("model.bin",content.size.toLong(),hash,"publisher/test","0".repeat(40))))
    private val manifest="{\"schemaVersion\":1,\"modelId\":\"${spec.id}\"}".toByteArray()
    private fun zip(vararg files: Pair<String,ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip -> files.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
    }.toByteArray()
    @Test fun completePinnedArchiveStagesWithoutPublishingAPartialModel() {
        val parent=Files.createTempDirectory("ct2-zip").toFile()
        try {
            val (selected,root)=Ct2Archive.stage(parent,ByteArrayInputStream(zip(Ct2Archive.MANIFEST to manifest,"model.bin" to content)),listOf(spec))
            assertEquals(spec.id,selected.id);assertArrayEquals(content,java.io.File(root,"model.bin").readBytes())
        } finally { parent.deleteRecursively() }
    }
    @Test fun nestedManifestTraversalExtraFilesAndChangedWeightsAreRejected() {
        val cases=listOf(
            zip("folder/${Ct2Archive.MANIFEST}" to manifest,"model.bin" to content),
            zip("../escape" to content,Ct2Archive.MANIFEST to manifest),
            zip(Ct2Archive.MANIFEST to manifest,"model.bin" to content,"extra" to content),
            zip(Ct2Archive.MANIFEST to manifest,"model.bin" to ByteArray(content.size)),
            zip(Ct2Archive.MANIFEST to "{\"schemaVersion\":2,\"modelId\":\"${spec.id}\"}".toByteArray(),"model.bin" to content),
        )
        cases.forEach { bytes ->
            val parent=Files.createTempDirectory("ct2-reject").toFile()
            try { assertThrows(Exception::class.java) { Ct2Archive.stage(parent,ByteArrayInputStream(bytes),listOf(spec)) } }
            finally { parent.deleteRecursively() }
        }
    }
    @Test fun cancelledImportCannotLeaveASelectableDirectory() {
        val parent=Files.createTempDirectory("ct2-cancel").toFile()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                Ct2Archive.stage(parent,ByteArrayInputStream(zip(Ct2Archive.MANIFEST to manifest,"model.bin" to content)),listOf(spec)) { throw java.util.concurrent.CancellationException("cancelled") }
            }
            assertTrue(parent.listFiles().orEmpty().isEmpty())
        } finally { parent.deleteRecursively() }
    }
}
