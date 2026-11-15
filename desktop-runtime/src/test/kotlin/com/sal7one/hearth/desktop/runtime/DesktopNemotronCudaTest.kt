package com.sal7one.hearth.desktop.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopNemotronCudaTest {
    private val backend = "hearth_nemotron_cuda.dll"
    private val kernels = "hearth_nemotron_cublas.dll"
    private val hashes = mapOf(backend to "a".repeat(64), kernels to "b".repeat(64), NEMOTRON_CUDA_NOTICES to "c".repeat(64),
        "hearth_nemotron.dll" to "d".repeat(64))

    /** The receipt build-native.py writes for a bundle that carries the Nemotron CUDA backend. */
    private fun manifest(receipt: JsonObject.() -> Map<String, JsonElement> = { this }, files: Map<String, String> = hashes) = buildJsonObject {
        put("schemaVersion", 1); put("platform", "windows-x64")
        putJsonObject("files") { files.forEach { (name, hash) -> put(name, hash) } }
        put("nemotronCuda", JsonObject(buildJsonObject {
            put("schemaVersion", 1); put("backend", backend); put("blas", kernels); put("notices", NEMOTRON_CUDA_NOTICES)
            put("toolkitVersion", "12.9.1"); put("toolkitTreeSha256", "e".repeat(64)); put("minimumComputeCapability", "8.0")
            put("driver", "nvcuda.dll"); put("requiresSystemDriver", true); put("nvidiaLibrariesBundled", false)
            putJsonObject("sha256") { listOf(backend, kernels, NEMOTRON_CUDA_NOTICES).forEach { put(it, hashes.getValue(it)) } }
        }.receipt()))
    }

    private fun order(manifest: JsonObject, platform: String = "windows-x64") =
        nativeDependencyOrder(NEMOTRON_CUDA_LIBRARY, platform, nativeBundleFiles(manifest).keys, manifest)

    @Test fun theKernelsLoadFirstAndTheLicenceTextIsExtractedWithTheBackend() {
        val manifest = manifest()
        assertEquals(listOf(kernels), order(manifest))
        assertEquals(setOf(backend, kernels, NEMOTRON_CUDA_NOTICES), nativeExtractionFiles(NEMOTRON_CUDA_LIBRARY, "windows-x64", hashes.keys, manifest))
        // The CPU backend beside it needs nothing from the receipt.
        assertEquals(emptyList(), nativeDependencyOrder("hearth_nemotron", "windows-x64", hashes.keys, manifest))
    }

    @Test fun aBundleWithoutTheReceiptOrForAnotherPlatformHasNoCudaBackend() {
        val bare = JsonObject(manifest() - "nemotronCuda")
        assertContains(assertFailsWith<IllegalStateException> { order(bare) }.message.orEmpty(), "no Nemotron CUDA backend")
        assertContains(assertFailsWith<IllegalStateException> { order(manifest(), "macos-arm64") }.message.orEmpty(), "Windows x64 only")
        assertFailsWith<IllegalStateException> { nativeDependencyOrder(NEMOTRON_CUDA_LIBRARY, "windows-x64", hashes.keys) }
    }

    @Test fun aReceiptThatAdmitsNvidiaLibrariesOrAnAppLocalDriverIsRefused() {
        fun changed(key: String, value: JsonElement) = manifest({ this + (key to value) })
        for ((key, value) in listOf("nvidiaLibrariesBundled" to JsonPrimitive(true), "requiresSystemDriver" to JsonPrimitive(false),
            "driver" to JsonPrimitive("cudart64_12.dll"))) {
            assertContains(assertFailsWith<IllegalStateException>(key) { order(changed(key, value)) }.message.orEmpty(), "driver policy")
        }
        val bundledDriver = manifest(files = hashes + ("NVCUDA.DLL" to "f".repeat(64)))
        assertContains(assertFailsWith<IllegalStateException> { order(bundledDriver) }.message.orEmpty(), "never the app-local bundle")
    }

    @Test fun renamedMissingOrAlteredFilesAreRefusedBeforeAnythingLoads() {
        fun changed(key: String, value: JsonElement) = manifest({ this + (key to value) })
        // NVIDIA's own library name must never stand in for the kernels.
        assertContains(assertFailsWith<IllegalStateException> { order(changed("blas", JsonPrimitive("cublas64_12.dll"))) }.message.orEmpty(), "Invalid Nemotron CUDA receipt")
        assertContains(assertFailsWith<IllegalStateException> { order(changed("schemaVersion", JsonPrimitive(2))) }.message.orEmpty(), "Invalid Nemotron CUDA receipt")
        assertContains(assertFailsWith<IllegalStateException> { order(changed("toolkitVersion", JsonPrimitive("11.8"))) }.message.orEmpty(), "build record")
        val recorded = manifest().getValue("nemotronCuda").jsonObject.getValue("sha256").jsonObject
        val extra = changed("sha256", JsonObject(recorded + ("cublasLt64_12.dll" to JsonPrimitive("f".repeat(64)))))
        assertContains(assertFailsWith<IllegalStateException> { order(extra) }.message.orEmpty(), "exactly its own files")
        val altered = manifest(files = hashes + (kernels to "0".repeat(64)))
        assertContains(assertFailsWith<IllegalStateException> { order(altered) }.message.orEmpty(), kernels)
        val missing = manifest(files = hashes - NEMOTRON_CUDA_NOTICES)
        assertContains(assertFailsWith<IllegalStateException> { order(missing) }.message.orEmpty(), NEMOTRON_CUDA_NOTICES)
    }

    @Test fun onDiskTheKernelsAreVerifiedByHashBeforeTheyAreLoaded() {
        val directory = Files.createTempDirectory("hearth-nemotron-cuda-")
        try {
            Files.write(directory.resolve(kernels), byteArrayOf(1, 2, 3))
            val failure = assertFailsWith<IllegalStateException> { verifiedNativeDependencies(directory, listOf(kernels), hashes) }
            assertContains(failure.message.orEmpty(), "checksum failed")
        } finally { directory.toFile().deleteRecursively() }
    }
}
