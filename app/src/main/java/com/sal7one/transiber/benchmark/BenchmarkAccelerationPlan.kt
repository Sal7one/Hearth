package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.speech.SpeechBackend

/** Compare the same artifact and input without changing anyone's saved engine preference. */
internal object BenchmarkAccelerationPlan {
    fun expand(models: List<BenchmarkCandidate>, compare: Boolean): List<BenchmarkCandidate> {
        require(models.size <= 12) { "Choose no more than 12 models" }
        if (!compare) return models
        return models.flatMap { model ->
            val modes = when {
                model.translation != null || model.profile?.backend == SpeechBackend.TRANSCRIBEPSY -> listOf("cpu", "vulkan", "opencl")
                model.profile?.backend in setOf(SpeechBackend.COHERE, SpeechBackend.FUNASR_MLT, SpeechBackend.VOXTRAL_REALTIME) -> listOf("cpu", "vulkan")
                model.route.startsWith("Whisper /") -> listOf("cpu", "vulkan")
                else -> listOf("cpu")
            }
            modes.map { mode -> model.copy(label = "${model.label} · ${mode.uppercase(java.util.Locale.ROOT)}", acceleration = mode) }
        }
    }

    fun identity(model: BenchmarkCandidate): String = model.id + (model.acceleration?.let { "@$it" } ?: "")
}
