package com.sal7one.transiber.models

import com.sal7one.common_jni.translation.TranslationAcceleration
import com.sal7one.transiber.caption.SpeechAccelerationStore

/** Runtime paths, not a claim that a driver/model has passed device inference. */
internal enum class ModelHardwareFilter(val key: String) {
    ALL("all"), GPU("gpu"), VULKAN("vulkan"), OPENCL("opencl"), CPU_ONLY("cpu-only");

    fun matches(paths: Set<String>): Boolean = when (this) {
        ALL -> true
        GPU -> paths.isNotEmpty()
        VULKAN -> "vulkan" in paths
        OPENCL -> "opencl" in paths
        CPU_ONLY -> paths.isEmpty()
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ALL
    }
}

internal fun speechGpuPaths(artifact: SpeechArtifact): Set<String> =
    if ("arm64-v8a" !in artifact.supportedAbis) emptySet()
    else SpeechAccelerationStore.choices(artifact.engine).filterTo(linkedSetOf()) { it != "cpu" }

/** GGUF uses the shared translation session; CT2/Marian currently use CPU. */
internal fun translationGpuPaths(gguf: Boolean): Set<String> =
    if (gguf) TranslationAcceleration.entries.filter { it != TranslationAcceleration.CPU }.mapTo(linkedSetOf()) { it.id }
    else emptySet()
