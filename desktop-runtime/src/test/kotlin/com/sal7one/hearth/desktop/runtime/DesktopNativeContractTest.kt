package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopQwenNative
import com.sal7one.common_jni.desktop.DesktopWhisperNative
import com.sal7one.common_jni.translation.LocalTranslationNative
import com.sal7one.common_jni.voice.SupertonicNative
import org.junit.Assume.assumeTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.*

class DesktopNativeContractTest {
    @Test fun serializedBundleUsesOpaqueNativeResourcesAndRejectsModifiedPayloads() {
        val platform = DesktopNativeLibraries.platform
        val manifest = javaClass.getResourceAsStream("/native/$platform/manifest.json")?.use { it.readBytes() }
        assumeTrue("Build the current host native bundle before resource tests", manifest != null)
        val files = nativeBundleFiles(Json.parseToJsonElement(requireNotNull(manifest).toString(Charsets.UTF_8)).jsonObject)
        for ((filename, hash) in files) {
            var requested = ""
            val bytes = readPackagedNative(platform, filename, hash) { resource ->
                requested = resource
                javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
            }
            assertEquals(hash, sha256(bytes))
            if (nativeResourceFilename(filename) != filename) {
                assertTrue(requested.endsWith(".payload"))
                assertNull(javaClass.getResource("/native/$platform/$filename"), "No raw native entry may remain available for Compose to rewrite")
                val changed = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                val error = assertFailsWith<IllegalStateException> { readPackagedNative(platform, filename, hash) { changed } }
                assertContains(error.message.orEmpty(), "checksum failed")
            }
        }
    }
    @Test fun packagedNativeRuntimesExposeRealProtocolsAndRejectInvalidModels() {
        assumeTrue("Build the current host native bundle before JNI tests", javaClass.getResource("/native/${DesktopNativeLibraries.platform}/manifest.json") != null)
        val directory = Files.createTempDirectory("hearth-desktop-native-contract")
        try {
            DesktopNativeLibraries.configure(directory)
            assertNull(DesktopNativeLibraries.readiness("hearth_desktop_whisper"))
            assertNull(DesktopNativeLibraries.readiness("transiber_translation"))
            assertNull(DesktopNativeLibraries.readiness("hearth_supertonic_jni"))
            val qwenStaged = javaClass.getResource("/native/${DesktopNativeLibraries.platform}/libhearth_desktop_qwen3_asr.dylib.payload") != null
            if (qwenStaged) {
                assertNull(DesktopNativeLibraries.readiness(QwenSpeechModel.LIBRARY))
                assertEquals(QwenSpeechModel.PROTOCOL_VERSION, DesktopQwenNative.protocolVersion())
            }
            assertEquals(DesktopWhisperNative.PROTOCOL_VERSION, DesktopWhisperNative.protocolVersion())
            assertEquals(5, LocalTranslationNative.promptProtocolVersion())
            val model = directory.resolve("غير صالح🙂.ggml")
            Files.write(model, ByteArray(64))
            val error = assertFailsWith<IllegalStateException> { DesktopWhisperNative.create(model.toString().toByteArray(), "ar".toByteArray(), 2, false) }
            assertContains(error.message.orEmpty(), "غير صالح🙂")
            val threadError = assertFailsWith<IllegalStateException> { DesktopWhisperNative.create(model.toString().toByteArray(), "ar".toByteArray(), 0, false) }
            assertContains(threadError.message.orEmpty(), "threads")
            val invalidBackend = assertFailsWith<IllegalStateException> { DesktopWhisperNative.createWithBackend(model.toString().toByteArray(), "en".toByteArray(), 2, 9, 0) }
            assertContains(invalidBackend.message.orEmpty(), "backend")
            val invalidDevice = assertFailsWith<IllegalStateException> { DesktopWhisperNative.createWithBackend(model.toString().toByteArray(), "en".toByteArray(), 2, 0, 1) }
            assertContains(invalidDevice.message.orEmpty(), "device index")
            val translationThreads = assertFailsWith<RuntimeException> { LocalTranslationNative.create(model.toString().toByteArray(), 0, 0, 0) }
            assertContains(translationThreads.message.orEmpty(), "threads")
            val translationPlacement = assertFailsWith<RuntimeException> { LocalTranslationNative.create(model.toString().toByteArray(), 0, 2, 1) }
            assertContains(translationPlacement.message.orEmpty(), "layer policy")
            val invalidTranslationBackend = assertFailsWith<RuntimeException> { LocalTranslationNative.createWithBackend(model.toString().toByteArray(), 0, 2, 9, 0) }
            assertContains(invalidTranslationBackend.message.orEmpty(), "backend")
            val invalidTranslationDevice = assertFailsWith<RuntimeException> { LocalTranslationNative.createWithBackend(model.toString().toByteArray(), 0, 2, 0, -1) }
            assertContains(invalidTranslationDevice.message.orEmpty(), "index")
            if (!DesktopWhisperNative.metalAvailable()) {
                val unavailable = assertFailsWith<IllegalStateException> { DesktopWhisperNative.create(model.toString().toByteArray(), "ar".toByteArray(), 2, true) }
                assertContains(unavailable.message.orEmpty(), "Metal acceleration requested")
            }
            if (!LocalTranslationNative.metalAvailable()) {
                val unavailable = assertFailsWith<RuntimeException> { LocalTranslationNative.create(model.toString().toByteArray(), 0, 2, 99) }
                assertContains(unavailable.message.orEmpty(), "Metal acceleration requested")
            }
            for ((available, create) in listOf(
                DesktopWhisperNative.gpuAvailable(DesktopCompute.CUDA, -1) to { DesktopWhisperNative.createWithBackend(model.toString().toByteArray(), "en".toByteArray(), 2, DesktopCompute.CUDA, 0) },
                LocalTranslationNative.gpuAvailable(DesktopCompute.CUDA, -1) to { LocalTranslationNative.createWithBackend(model.toString().toByteArray(), 0, 2, DesktopCompute.CUDA, 0) },
            )) if (!available) assertContains(assertFailsWith<RuntimeException> { create() }.message.orEmpty(), "CUDA acceleration requested")
            DesktopEngines(com.sal7one.hearth.core.CredentialResolver { null }, directory).use { engines ->
                val descriptors = engines.capabilities().associateBy { it.id }
                assertEquals(DesktopWhisperNative.metalAvailable(), "metal" in descriptors.getValue("whisper-local").acceleration)
                assertEquals(LocalTranslationNative.metalAvailable(), "metal" in descriptors.getValue("llama-translation-local").acceleration)
                assertEquals(DesktopWhisperNative.gpuAvailable(DesktopCompute.CUDA, -1), "cuda" in descriptors.getValue("whisper-local").acceleration)
                assertEquals(LocalTranslationNative.gpuAvailable(DesktopCompute.CUDA, -1), "cuda" in descriptors.getValue("llama-translation-local").acceleration)
                assertEquals(decodeGpuDevices("cuda", DesktopWhisperNative.gpuDevices(DesktopCompute.CUDA)), engines.gpuDevices("whisper-local", "cuda"))
                assertEquals(setOf("cpu"), descriptors.getValue("supertonic-local").acceleration)
                assertEquals(setOf("cpu"), descriptors.getValue("omnivoice-tts-local").acceleration)
                if (qwenStaged) assertEquals(setOf("cpu"), descriptors.getValue(QwenSpeechModel.ENGINE_ID).acceleration)
            }
            assertFailsWith<IllegalStateException> { DesktopWhisperNative.transcribe(0, floatArrayOf(0f)) }
            assertFailsWith<IllegalStateException> { DesktopWhisperNative.transcribeLive(0, floatArrayOf(0f)) }
            assertFailsWith<RuntimeException> { LocalTranslationNative.translate(0, "hello".toByteArray()) }
            assertFailsWith<IllegalStateException> { DesktopWhisperNative.executionInfo(0) }
            assertFailsWith<RuntimeException> { LocalTranslationNative.executionInfo(0) }
            assertFailsWith<IllegalStateException> { SupertonicNative.synthesize(0, longArrayOf(1), FloatArray(12800), FloatArray(128), 5, 1f) }
            if (qwenStaged) {
                assertFailsWith<IllegalStateException> { DesktopQwenNative.transcribe(0, floatArrayOf(0f)) }
                assertFailsWith<IllegalStateException> { DesktopQwenNative.executionInfo(0) }
                DesktopQwenNative.cancel(0); DesktopQwenNative.destroy(0)
            }
            DesktopWhisperNative.cancel(0); DesktopWhisperNative.destroy(0)
            LocalTranslationNative.cancel(0); LocalTranslationNative.destroy(0)
            SupertonicNative.cancel(0); SupertonicNative.destroy(0)
        } finally { directory.toFile().deleteRecursively() }
    }
}
