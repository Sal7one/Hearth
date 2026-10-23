package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.speech.SpeechBackend
import com.sal7one.transiber.caption.captionEngine

/** Compare the same artifact and input without changing anyone's saved engine preference. */
internal object BenchmarkAccelerationPlan {
    suspend fun resolve(context: android.content.Context,models: List<BenchmarkCandidate>,compare: Boolean): List<BenchmarkCandidate> =
        expand(models,compare).map { candidate ->
            if(candidate.acceleration!=null)candidate else candidate.copy(acceleration=when {
                candidate.translation!=null -> com.sal7one.transiber.translation.ConversationTranslationSettings.acceleration(context).id
                candidate.profile!=null -> com.sal7one.transiber.caption.SpeechAccelerationStore.current(context,candidate.profile.captionEngine)
                candidate.route.startsWith("Whisper /") -> com.sal7one.transiber.caption.SpeechAccelerationStore.current(context,com.sal7one.transiber.caption.CaptionEngineChoice.WHISPER)
                else -> "cpu"
            })
        }.distinctBy(::identity)
    fun expand(models: List<BenchmarkCandidate>, compare: Boolean): List<BenchmarkCandidate> {
        val unique = models.distinctBy(::identity)
        if (!compare) return unique
        return unique.flatMap { model ->
            val modes = when {
                model.translation != null || model.profile?.backend == SpeechBackend.TRANSCRIBEPSY -> listOf("cpu", "vulkan", "opencl")
                model.profile?.backend in setOf(SpeechBackend.COHERE, SpeechBackend.FUNASR_MLT, SpeechBackend.VOXTRAL_REALTIME) -> listOf("cpu", "vulkan")
                model.route.startsWith("Whisper /") -> listOf("cpu", "vulkan")
                else -> listOf("cpu")
            }
            modes.map { mode -> model.copy(label = "${model.label} · ${mode.uppercase(java.util.Locale.ROOT)}", acceleration = mode) }
        }.distinctBy(::identity)
    }

    fun identity(model: BenchmarkCandidate): String = model.id + (model.acceleration?.let { "@$it" } ?: "") +
        (model.recognitionLanguage?.let { "#language=$it" } ?: "")
}
