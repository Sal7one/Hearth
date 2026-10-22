package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import kotlin.test.*

class DesktopComputeTest {
    @Test fun explicitBackendAndDeviceArePreservedWithoutCpuFallback() {
        assertEquals(DesktopCompute("cpu", 0, 0), desktopCompute(selection()))
        assertEquals(DesktopCompute("metal", 1, 0), desktopCompute(selection("metal")))
        assertEquals(DesktopCompute("cuda", 2, 3), desktopCompute(selection("cuda", "3")))
        assertEquals(DesktopCompute("cpu", 0, 0), desktopCompute(selection("cpu", "3")))
        for (mode in listOf("auto", "vulkan", "CUDA")) assertFailsWith<IllegalStateException> { desktopCompute(selection(mode)) }
        for (device in listOf("-1", "nvidia", "2147483648")) assertFailsWith<IllegalArgumentException> { desktopCompute(selection("cuda", device)) }
    }
    @Test fun cpuOnlyFamiliesRejectGpuRequests() {
        for (id in listOf("qwen3-asr-local", "marian-local", "supertonic-local", "ming-local")) {
            requireCpuAcceleration(EngineSelection(id))
            for (gpu in listOf("metal", "cuda")) assertFailsWith<IllegalArgumentException> {
                requireCpuAcceleration(EngineSelection(id, options = mapOf("acceleration" to gpu)))
            }
        }
    }
    @Test fun gpuInventoryUsesRealIndicesAndUnknownMemoryStaysUnknown() {
        val metal = decodeGpuDevices("metal", """[{"index":0,"name":"Metal","description":"Apple GPU","memoryBytes":null}]""".toByteArray())
        assertEquals(listOf(DesktopGpuDevice("metal", 0, "Metal", "Apple GPU", null)), metal)
        val cuda = decodeGpuDevices("cuda", """[{"index":2,"name":"CUDA2","description":"NVIDIA device","memoryBytes":123456}]""".toByteArray())
        assertEquals(2, cuda.single().index); assertEquals(123456L, cuda.single().memoryBytes)
        assertFailsWith<IllegalStateException> { decodeGpuDevices("cuda", """[{"index":-1,"name":"bad","description":"bad"}]""".toByteArray()) }
        assertFailsWith<IllegalStateException> { decodeGpuDevices("cuda", """[{"index":0,"name":"a","description":"a"},{"index":0,"name":"b","description":"b"}]""".toByteArray()) }
    }
    private fun selection(backend: String? = null, device: String? = null) = EngineSelection("whisper-local", options = buildMap {
        backend?.let { put("acceleration", it) }; device?.let { put("gpuDevice", it) }
    })
}
