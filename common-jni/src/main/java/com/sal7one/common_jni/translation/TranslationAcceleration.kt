package com.sal7one.common_jni.translation

/** Explicit GGUF execution policy. Existing models and installations stay on CPU. */
enum class TranslationAcceleration(val id: String) {
    CPU("cpu"), VULKAN("vulkan"), OPENCL("opencl");

    companion object {
        fun fromId(id: String?): TranslationAcceleration = entries.firstOrNull { it.id == id } ?: CPU
    }
}
