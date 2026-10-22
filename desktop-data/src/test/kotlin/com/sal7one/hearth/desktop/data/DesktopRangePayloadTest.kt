package com.sal7one.hearth.desktop.data

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Offline coverage for the authenticated append-only range TAR walk used by Magpie. */
class DesktopRangePayloadTest {
    @Test
    fun extractsOnlyFinalPinnedMembersFromConcatenatedRanges() {
        val keepA = "abc".toByteArray()
        val keepB = "hello".toByteArray()
        val oldConfig = "x".toByteArray()
        val newConfig = "ok".toByteArray()
        val junk = "ignored".toByteArray()
        val stop = "model_weights".toByteArray()
        val segmentOne = header("keep-a", keepA.size) + pad(keepA) + header("model_config.yaml", oldConfig.size) + pad(oldConfig) + header("model_weights.ckpt", stop.size)
        assertEquals(2560, segmentOne.size)
        val segmentTwo = header("keep-b", keepB.size) + pad(keepB) + header("model_config.yaml", newConfig.size) + pad(newConfig) + header("junk", junk.size) + pad(junk)
        val payloadBytes = segmentOne + segmentTwo
        val directory = Files.createTempDirectory("hearth-range-test-")
        try {
            val payload = directory.resolve("ranges.bin")
            Files.write(payload, payloadBytes)
            val members = listOf(
                DesktopModelArchiveFile("keep-a", keepA.size.toLong(), sha(keepA)),
                DesktopModelArchiveFile("keep-b", keepB.size.toLong(), sha(keepB)),
                DesktopModelArchiveFile("model_config.yaml", newConfig.size.toLong(), sha(newConfig)),
            )
            val packageInfo = DesktopRangePackage(
                "https://example.invalid/model.nemo", 9_999, "0".repeat(64),
                payloadBytes.size.toLong(), sha(payloadBytes),
                listOf(LongRange(0, 2559), LongRange(2560, 5631)), members,
            )
            val staging = directory.resolve("staging")
            Files.createDirectories(staging)
            DesktopModelLibrary(directory).extractRangePayload(payload, packageInfo, staging)
            val extracted = Files.list(staging).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
            assertEquals(listOf("keep-a", "keep-b", "model_config.yaml"), extracted)
            assertContentEquals(keepA, Files.readAllBytes(staging.resolve("keep-a")))
            assertContentEquals(keepB, Files.readAllBytes(staging.resolve("keep-b")))
            assertContentEquals(newConfig, Files.readAllBytes(staging.resolve("model_config.yaml")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsAPayloadThatOmitsAPinnedMember() {
        val data = "abc".toByteArray()
        val payloadBytes = header("keep-a", data.size) + pad(data)
        val directory = Files.createTempDirectory("hearth-range-test-")
        try {
            val payload = directory.resolve("ranges.bin")
            Files.write(payload, payloadBytes)
            val packageInfo = DesktopRangePackage(
                "https://example.invalid/model.nemo", 999, "0".repeat(64), payloadBytes.size.toLong(), sha(payloadBytes),
                listOf(LongRange(0, payloadBytes.size.toLong() - 1)),
                listOf(DesktopModelArchiveFile("keep-a", data.size.toLong(), sha(data)),
                       DesktopModelArchiveFile("missing", 1, "0".repeat(64))),
            )
            val staging = directory.resolve("staging")
            Files.createDirectories(staging)
            val failure = runCatching { DesktopModelLibrary(directory).extractRangePayload(payload, packageInfo, staging) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message.orEmpty().contains("missing components"), "Unexpected failure: $failure")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun pad(bytes: ByteArray): ByteArray = ByteArray(((bytes.size + 511) / 512) * 512).also { bytes.copyInto(it) }

    private fun header(name: String, size: Int): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        "%011o".format(size).toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        header[262] = 0
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        header.fill(' '.code.toByte(), 148, 156)
        val checksum = header.sumOf { (it.toInt() and 0xff).toLong() }
        "%06o".format(checksum).toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        header[154] = 0
        header[155] = ' '.code.toByte()
        return header
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
