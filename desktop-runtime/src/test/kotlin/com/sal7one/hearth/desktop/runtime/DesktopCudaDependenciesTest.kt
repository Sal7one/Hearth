package com.sal7one.hearth.desktop.runtime

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DesktopCudaDependenciesTest {
    @Test fun actualStagerShapeLoadsVerifiedCrtThenCudaInDependencyOrder() {
        for (version in listOf("12.8.1", "13.0.2")) {
            val fixture = manifest(version)
            val files = nativeBundleFiles(fixture)
            val ordered = nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", files.keys, fixture)
            val major = version.substringBefore('.')
            assertEquals(microsoft + listOf("cudart64_$major.dll", "cublaslt64_$major.dll", "cublas64_$major.dll"), ordered)
            assertEquals(ordered, nativeDependencyOrder("transiber_translation", "windows-x64", files.keys, fixture))
            val evidence = setOf("NVIDIA-CUDA-EULA.txt", "NVIDIA-CUDA-NOTICE.txt", "nvidia-cuda-provenance.json")
            assertEquals(ordered.toSet() + evidence + "hearth_desktop_whisper.dll",
                nativeExtractionFiles("hearth_desktop_whisper", "windows-x64", files.keys, fixture))
            assertEquals(ordered.toSet() + evidence + "transiber_translation.dll",
                nativeExtractionFiles("transiber_translation", "windows-x64", files.keys, fixture))
        }
    }
    @Test fun missingAlteredOrUnrecordedCudaCodeNeverLoadsAnEarlierDll() {
        val fixture = manifest()
        val hashes = nativeBundleFiles(fixture)
        val ordered = nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", hashes.keys, fixture)
        val directory = Files.createTempDirectory("hearth-cuda-preflight-")
        try {
            hashes.forEach { (name, _) -> Files.writeString(directory.resolve(name), "fixture-$name") }
            assertEquals(ordered.map(directory::resolve), verifiedNativeDependencies(directory, ordered, hashes))
            Files.writeString(directory.resolve(ordered.last()), "tampered")
            var loaded = 0
            assertFailsWith<IllegalStateException> { verifiedNativeDependencies(directory, ordered, hashes).forEach { loaded++ } }
            assertEquals(0, loaded)
            val withoutCrt = hashes.keys - microsoft.last()
            assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", withoutCrt, fixture) }
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun driverUnknownDllHashMismatchAndUnverifiedBackendEvidenceAreRejected() {
        val valid = manifest()
        val files = nativeBundleFiles(valid)
        for (changed in listOf(
            changedReceipt(valid, "driver", JsonPrimitive("app-nvcuda.dll")),
            changedReceipt(valid, "loadOrder", buildJsonArray { add("cublas64_12.dll"); add("cublaslt64_12.dll"); add("cudart64_12.dll") }),
            changedReceipt(valid, "entries", buildJsonArray { add("hearth_desktop_whisper.dll") }),
            changedReceipt(valid, "files", buildJsonArray { add("evil.dll") }),
            JsonObject(valid - "cudaRuntime"),
        )) {
            assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", files.keys, changed) }
            assertFailsWith<IllegalStateException> { nativeExtractionFiles("hearth_desktop_whisper", "windows-x64", files.keys, changed) }
        }
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", files.keys + "nvcuda.dll", valid) }
        val badFiles = JsonObject(valid.getValue("files").jsonObject + ("cublas64_12.dll" to JsonPrimitive("0".repeat(64))))
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", files.keys, JsonObject(valid + ("files" to badFiles))) }
        val libraries = valid.getValue("libraries").jsonObject
        val unknown = JsonObject(libraries.getValue("hearth_desktop_whisper.dll").jsonObject + ("backendProbe" to JsonPrimitive("unavailable")))
        val unverified = JsonObject(valid + ("libraries" to JsonObject(libraries + ("hearth_desktop_whisper.dll" to unknown))))
        assertFailsWith<IllegalStateException> { nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", files.keys, unverified) }
    }
    @Test fun legacyCpuBundlesAndSeparateOnnxStagesKeepTheirExistingDependencies() {
        val cpu = buildJsonObject { putJsonObject("files") { put("hearth_desktop_whisper.dll", "1".repeat(64)) } }
        assertTrue(nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", nativeBundleFiles(cpu).keys, cpu).isEmpty())
        val fixture = manifest()
        val files = nativeBundleFiles(fixture).keys + "onnxruntime.dll"
        assertEquals(microsoft + "onnxruntime.dll", nativeDependencyOrder("hearth_supertonic_jni", "windows-x64", files, fixture))
        val libraries = fixture.getValue("libraries").jsonObject
        val unknown = JsonObject(libraries.getValue("hearth_desktop_whisper.dll").jsonObject + ("backendProbe" to JsonPrimitive("unavailable")))
        val noReceipt = JsonObject((fixture - "cudaRuntime") + ("libraries" to JsonObject(libraries + ("hearth_desktop_whisper.dll" to unknown))))
        assertTrue(nativeDependencyOrder("hearth_desktop_whisper", "windows-x64", files, noReceipt).isEmpty())
    }
    private fun changedReceipt(source: JsonObject, key: String, value: JsonElement) =
        JsonObject(source + ("cudaRuntime" to JsonObject(source.getValue("cudaRuntime").jsonObject + (key to value))))

    private fun manifest(version: String = "12.8.1"): JsonObject {
        val major = version.substringBefore('.')
        val dependencies = listOf("cudart64_$major.dll", "cublaslt64_$major.dll", "cublas64_$major.dll")
        val evidence = listOf("NVIDIA-CUDA-EULA.txt", "NVIDIA-CUDA-NOTICE.txt", "nvidia-cuda-provenance.json")
        val files = (microsoft + entries + dependencies + evidence).associateWith { sha256("fixture-$it".toByteArray()) }
        return buildJsonObject {
            putJsonObject("files") { files.forEach { (file, hash) -> put(file, hash) } }
            putJsonObject("libraries") { entries.forEach { name -> putJsonObject(name) {
                put("sha256", files.getValue(name)); put("backendProbe", "native-export-v1"); put("sourceSetSha256", "a".repeat(64))
                putJsonArray("compiledBackends") { add("cpu"); add("cuda") }
            } } }
            putJsonObject("cudaRuntime") {
                put("schemaVersion", 1); put("toolkitVersion", version); put("driver", "nvcuda.dll"); put("requiresSystemDriver", true)
                put("license", "NVIDIA-CUDA-EULA.txt"); put("provenance", "nvidia-cuda-provenance.json")
                putJsonArray("files") { dependencies.forEach { add(it) } }
                putJsonArray("loadOrder") { dependencies.forEach { add(it) } }
                putJsonArray("entries") { entries.forEach { add(it) } }
                putJsonObject("sha256") { (dependencies + evidence).forEach { put(it, files.getValue(it)) } }
            }
        }
    }
    companion object {
        private val microsoft = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll")
        private val entries = listOf("hearth_desktop_whisper.dll", "transiber_translation.dll")
    }
}
