package com.sal7one.hearth.desktop.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNativeExtractionTest {
    @Test fun speechExtractsOnlyItsRequestedLibraryAndVerifiedDependencyClosure() {
        val names = setOf("libhearth_desktop_qwen3_asr.dylib", "libonnxruntime.1.20.1.dylib", "libming_jni.dylib", "libhearth_nemotron.dylib")
        val manifest = buildJsonObject {
            put("files", buildJsonObject { names.forEach { put(it, JsonPrimitive("0".repeat(64))) } })
        }
        assertEquals(setOf("libhearth_desktop_qwen3_asr.dylib", "libonnxruntime.1.20.1.dylib"),
            nativeExtractionFiles(QwenSpeechModel.LIBRARY, "macos-arm64", names, manifest))
        // A backend opened later through libraryPath gets its own closure.
        assertEquals(setOf("libhearth_nemotron.dylib"), nativeExtractionFiles("hearth_nemotron", "macos-arm64", names, manifest))
        assertFailsWith<IllegalStateException> { nativeExtractionFiles("missing", "macos-arm64", names, manifest) }
        assertFailsWith<IllegalStateException> {
            nativeExtractionFiles(QwenSpeechModel.LIBRARY, "macos-arm64", names - "libonnxruntime.1.20.1.dylib", manifest)
        }
    }

    @Test fun extractionRechecksRequestedBytesAndLeavesOtherEnginesAlone() {
        val directory = Files.createTempDirectory("hearth-native-closure-")
        val payloads = mapOf("libspeech.dylib" to byteArrayOf(1, 2, 3), "libdependency.dylib" to byteArrayOf(4, 5, 6))
        val hashes = payloads.mapValues { sha256(it.value) } + ("libunused.dylib" to "0".repeat(64))
        val requested = mutableListOf<String>()
        val read: (String) -> ByteArray? = { resource ->
            requested += resource
            payloads[resource.substringAfterLast('/').removeSuffix(".payload")]
        }
        try {
            val untouched = directory.resolve("libunused.dylib")
            Files.write(untouched, byteArrayOf(99))
            extractPackagedNativeFiles("macos-arm64", directory, hashes, payloads.keys, read)
            assertEquals(2, requested.size)
            assertTrue(requested.all { it.endsWith(".payload") })
            assertContentEquals(byteArrayOf(99), Files.readAllBytes(untouched))
            extractPackagedNativeFiles("macos-arm64", directory, hashes, payloads.keys, read)
            assertEquals(2, requested.size, "Already verified requested bytes need no extraction")
            Files.write(directory.resolve("libspeech.dylib"), byteArrayOf(42))
            extractPackagedNativeFiles("macos-arm64", directory, hashes, payloads.keys, read)
            assertEquals(3, requested.size, "A changed requested file must be restored from pinned bytes")
            assertContentEquals(payloads.getValue("libspeech.dylib"), Files.readAllBytes(directory.resolve("libspeech.dylib")))
            assertFalse(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().endsWith(".part") } })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun modifiedPackagedDependencyFailsBeforeReplacingExistingFile() {
        val directory = Files.createTempDirectory("hearth-native-corrupt-")
        try {
            val destination = directory.resolve("libdependency.dylib")
            Files.write(destination, byteArrayOf(8))
            val error = assertFailsWith<IllegalStateException> {
                extractPackagedNativeFiles("macos-arm64", directory, mapOf("libdependency.dylib" to sha256(byteArrayOf(1))), setOf("libdependency.dylib")) { byteArrayOf(2) }
            }
            assertTrue(error.message.orEmpty().contains("checksum failed"))
            assertContentEquals(byteArrayOf(8), Files.readAllBytes(destination))
            assertEquals(1L, Files.list(directory).use { it.count() })
        } finally { directory.toFile().deleteRecursively() }
    }
}
