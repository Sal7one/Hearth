package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import kotlinx.serialization.json.*

internal data class DesktopCompute(val name: String, val backend: Int, val gpuDevice: Int) {
    companion object { const val CPU = 0; const val METAL = 1; const val CUDA = 2 }
}

internal fun desktopCompute(selection: EngineSelection): DesktopCompute {
    val name = selection.options["acceleration"] ?: "cpu"
    val backend = when (name) { "cpu" -> DesktopCompute.CPU; "metal" -> DesktopCompute.METAL; "cuda" -> DesktopCompute.CUDA
        else -> error("Unsupported desktop acceleration: $name") }
    val device = selection.options["gpuDevice"]?.let {
        requireNotNull(it.toIntOrNull()?.takeIf { value -> value >= 0 }) { "GPU device index must be a nonnegative integer" }
    } ?: 0
    // Keep a user's GPU index preference when switching temporarily to CPU. CPU never selects a GPU.
    return DesktopCompute(name, backend, if (backend == DesktopCompute.CPU) 0 else device)
}

internal fun requireCpuAcceleration(selection: EngineSelection) {
    require(desktopCompute(selection).backend == DesktopCompute.CPU) { "${selection.engineId} currently supports CPU acceleration only" }
}

data class DesktopGpuDevice(val backend: String, val index: Int, val name: String, val description: String, val memoryBytes: Long?)

internal fun decodeGpuDevices(backend: String, bytes: ByteArray): List<DesktopGpuDevice> {
    require(backend in setOf("metal", "cuda")) { "GPU inventory requires Metal or CUDA" }
    check(bytes.size <= 128 * 1024) { "Native GPU inventory exceeds its limit" }
    val devices = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
    check(devices.size <= 128) { "Native GPU inventory contains too many devices" }
    return devices.map { value ->
        val item = value.jsonObject
        val index = item.getValue("index").jsonPrimitive.int
        val name = item.getValue("name").jsonPrimitive.content
        val description = item.getValue("description").jsonPrimitive.content
        val memory = item["memoryBytes"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
        check(index >= 0 && name.isNotBlank() && name.length <= 1024 && description.length <= 4096 && (memory == null || memory >= 0)) { "Invalid native GPU device inventory" }
        DesktopGpuDevice(backend, index, name, description, memory)
    }.also { check(it.map(DesktopGpuDevice::index).distinct().size == it.size) { "Native GPU inventory contains duplicate indices" } }
}
