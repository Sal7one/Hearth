package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalTranslationAccelerationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model(): Pair<File, TranslationModelSpec> {
        val file = temporary.newFile().apply { writeText("test model bytes") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return file to TranslationCatalog.models.first().copy(bytes = file.length(), sha256 = hash)
    }
    private class Runtime : TranslationRuntime {
        var created = 0; var destroyed = 0; var cancelled = 0
        var loadFailure: Throwable? = null; var infoFailure: Throwable? = null
        var output = "translated"; var info = "Vulkan · test GPU · offload requested 99 layers"
        override fun protocolVersion() = 2
        override fun create(path: ByteArray, rawPrompt: Boolean): Long { created++; loadFailure?.let { throw it }; return 7 }
        override fun executionInfo(handle: Long): String { assertEquals(7L, handle); infoFailure?.let { throw it }; return info }
        override fun translate(handle: Long, prompt: ByteArray) = output.toByteArray()
        override fun cancel(handle: Long) { cancelled++ }
        override fun destroy(handle: Long) { assertEquals(7L, handle); destroyed++ }
    }
    @Test fun unknownAndMissingPreferencesDefaultToCpu() {
        assertEquals(TranslationAcceleration.CPU, TranslationAcceleration.fromId(null))
        assertEquals(TranslationAcceleration.CPU, TranslationAcceleration.fromId("future-backend"))
        assertEquals(TranslationAcceleration.VULKAN, TranslationAcceleration.fromId("vulkan"))
        assertEquals(TranslationAcceleration.OPENCL, TranslationAcceleration.fromId("opencl"))
    }
    @Test fun openClKernelCompileFailureRetainsActualCauseWithoutCpuFallback() {
        val (file, spec) = model()
        val runtime = Runtime().apply { loadFailure = IllegalStateException("clBuildProgram failed (-11): unsupported Adreno compiler") }
        val selected = mutableListOf<TranslationAcceleration>()
        val error = assertThrows(IllegalStateException::class.java) {
            LocalTranslationSession.openWithRuntime(file, spec, TranslationAcceleration.OPENCL) { selected += it; runtime }
        }
        assertTrue(error.message.orEmpty().contains("clBuildProgram failed (-11)"))
        assertTrue(error.message.orEmpty().contains("OpenCL"))
        assertTrue(error.message.orEmpty().contains("Select CPU"))
        assertEquals(listOf(TranslationAcceleration.OPENCL), selected)
        assertEquals(1, runtime.created)
        assertEquals(0, runtime.destroyed)
    }
    @Test fun openClConfiguresPrivateCacheBeforeInitializingRuntime() {
        val (file, spec) = model()
        val cache = temporary.newFolder("private-kernel-cache")
        val calls = mutableListOf<String>()
        val runtime = object : TranslationRuntime by Runtime() {
            override fun configure(cacheDirectory: File?) { assertEquals(cache, cacheDirectory); calls += "cache" }
            override fun protocolVersion(): Int { calls += "protocol"; return 2 }
        }
        LocalTranslationSession.openWithRuntime(file, spec, TranslationAcceleration.OPENCL, cache) { runtime }.use {
            assertEquals(listOf("cache", "protocol"), calls)
        }
    }
    @Test fun explicitGpuLoadFailureKeepsActualCauseWithoutTryingCpu() {
        val (file, spec) = model(); val runtime = Runtime().apply { loadFailure = IllegalStateException("driver allocation failed") }
        val selected = mutableListOf<TranslationAcceleration>()
        val error = assertThrows(IllegalStateException::class.java) {
            LocalTranslationSession.openWithRuntime(file, spec, TranslationAcceleration.VULKAN) { selected += it; runtime }
        }
        assertTrue(error.message.orEmpty().contains("driver allocation failed"))
        assertTrue(error.message.orEmpty().contains("Select CPU"))
        assertEquals(listOf(TranslationAcceleration.VULKAN), selected)
        assertEquals(1, runtime.created); assertEquals(0, runtime.destroyed)
    }
    @Test fun missingGpuRuntimeNeverLoadsCpuOrCreatesAModel() {
        val (file, spec) = model(); val runtime = object : TranslationRuntime by Runtime() {
            override fun protocolVersion(): Int = throw UnsatisfiedLinkError("missing Vulkan library")
        }
        val selected = mutableListOf<TranslationAcceleration>()
        val error = assertThrows(IllegalStateException::class.java) {
            LocalTranslationSession.openWithRuntime(file, spec, TranslationAcceleration.VULKAN) { selected += it; runtime }
        }
        assertTrue(error.message.orEmpty().contains("missing Vulkan library"))
        assertEquals(listOf(TranslationAcceleration.VULKAN), selected)
    }
    @Test fun metadataFailureRetiresCreatedHandleExactlyOnce() {
        val (file, spec) = model(); val runtime = Runtime().apply { infoFailure = IllegalStateException("execution metadata unavailable") }
        val error = assertThrows(IllegalStateException::class.java) {
            LocalTranslationSession.openWithRuntime(file, spec, TranslationAcceleration.VULKAN) { runtime }
        }
        assertEquals("execution metadata unavailable", error.message)
        assertEquals(1, runtime.created); assertEquals(1, runtime.destroyed)
    }
    @Test fun actualBackendIsRetainedAndEmptyInferenceRemainsAnError() = runBlocking {
        val (file, spec) = model(); val runtime = Runtime()
        val session = LocalTranslationSession.openWithRuntime(file, spec, TranslationAcceleration.VULKAN) { runtime }
        assertEquals(runtime.info, session.executionInfo)
        runtime.output = " "
        try { session.translate("hello", TranslationDirection("en", "ar")); fail("Empty output must fail") }
        catch (e: IllegalStateException) { assertEquals("Local translation returned empty text", e.message) }
        session.cancel(); session.close(); session.close(); session.cancel()
        assertEquals(1, runtime.cancelled); assertEquals(1, runtime.destroyed)
    }
}
