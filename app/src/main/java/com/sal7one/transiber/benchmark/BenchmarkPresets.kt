package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.PlatformTranslation
import com.sal7one.transiber.translation.TranslationOptions

/** Downloadable starting points, never claims of measured speed or translation quality. */
internal enum class BenchmarkPreset { SPEED, BALANCED, QUALITY }

internal data class SuggestedModel(val id: String, val label: String, val bytes: Long?, val kind: String)
internal data class BenchmarkPresetPlan(
    val preset: BenchmarkPreset,
    val speech: SuggestedModel?,
    val translation: SuggestedModel?,
)

internal object BenchmarkPresets {
    fun forPair(source: String, target: String): List<BenchmarkPresetPlan> =
        BenchmarkPreset.entries.map { preset ->
            BenchmarkPresetPlan(preset, speech(preset, source), translation(preset, source, target))
        }

    private fun speech(preset: BenchmarkPreset, source: String): SuggestedModel? {
        val ids = when (preset) {
            BenchmarkPreset.SPEED -> when (source) {
                "en" -> listOf("moonshine-tiny-en-v2", "nemotron-3.5-asr-0.6b")
                else -> listOf("omnilingual-ctc-300m-v2-int8", "nemotron-3.5-asr-0.6b")
            }
            BenchmarkPreset.BALANCED -> if (source == "ru") listOf("qwen3-asr-0.6b", "nemotron-3.5-asr-0.6b")
                else listOf("nemotron-3.5-asr-0.6b", "qwen3-asr-0.6b", "moonshine-base-en-v2")
            BenchmarkPreset.QUALITY -> if (source == "ru") listOf("nemotron-3.5-asr-0.6b", "qwen3-asr-0.6b")
                else listOf("qwen3-asr-0.6b", "nemotron-3.5-asr-0.6b", "moonshine-base-en-v2")
        }
        return ids.asSequence().mapNotNull(SpeechArtifactCatalog::find).firstOrNull { source in it.languages }
            ?.let { SuggestedModel(it.id, it.label, it.bytes, "speech") }
    }

    private fun translation(preset: BenchmarkPreset, source: String, target: String): SuggestedModel? {
        if (source == target || source == "auto") return null
        val direct = MarianPackage.pairs.firstOrNull { it.source == source && it.target == target }
        if (preset == BenchmarkPreset.SPEED && direct != null)
            return SuggestedModel(direct.id, TranslationOptions.label(direct.id), direct.downloadBytes, "marian")
        val cascade = MarianCascade.routes.firstOrNull { it.source == source && it.target == target }
        if (preset == BenchmarkPreset.SPEED && cascade != null)
            return SuggestedModel(cascade.id, TranslationOptions.label(cascade.id), cascade.downloadBytes, "cascade")
        if (preset == BenchmarkPreset.SPEED && PlatformTranslation.available &&
            source in TranslationOptions.mlKitCodes && target in TranslationOptions.mlKitCodes)
            return SuggestedModel(TranslationOptions.ML_KIT, TranslationOptions.label(TranslationOptions.ML_KIT), null, "mlkit")
        val ggufIds = when (preset) {
            BenchmarkPreset.SPEED -> listOf("milmmt-46-1b-q4", "hy-mt2-q3km")
            BenchmarkPreset.BALANCED -> listOf("milmmt-46-1b-q5", "hy-mt2-q4")
            BenchmarkPreset.QUALITY -> listOf("hy-mt2-q4", "milmmt-46-1b-q5")
        }
        val gguf = ggufIds.asSequence().mapNotNull { id -> TranslationCatalog.models.firstOrNull { it.id == id } }
            .firstOrNull { it.supports(source, target) }
        if (gguf != null) return SuggestedModel(gguf.id, gguf.label, gguf.bytes, "gguf")
        return if (PlatformTranslation.available && source in TranslationOptions.mlKitCodes && target in TranslationOptions.mlKitCodes)
            SuggestedModel(TranslationOptions.ML_KIT, TranslationOptions.label(TranslationOptions.ML_KIT), null, "mlkit")
        else null
    }
}

internal data class BenchmarkLeaders(
    val candidates: List<BenchmarkResult>,
    val fastest: BenchmarkResult?,
    val balanced: BenchmarkResult?,
    val mostAccurate: BenchmarkResult?,
)

/** Compare only the same phone, source/target and exact input hash. Failed/empty runs cannot win. */
internal object BenchmarkComparison {
    fun latest(results: List<BenchmarkResult>, source: String, target: String): BenchmarkLeaders? {
        val matching = results.filter { it.source == source && it.target == target }
        val seed = matching.firstOrNull() ?: return null
        val comparable = matching.filter { it.inputHash == seed.inputHash && it.device == seed.device }
            .distinctBy { it.identity }.filter { result ->
                result.error == null && result.computeMs.size >= 3 && result.computeMs.all { it.isFinite() && it >= 0 } &&
                    result.texts.lastOrNull()?.isNotBlank() == true &&
                    (target.isNotBlank() || result.samples.none { it.silence && it.text.isNotBlank() })
            }
        fun time(result: BenchmarkResult) = BenchmarkMetrics.median(result.computeMs.drop(1))
        fun quality(result: BenchmarkResult): Double? = if (target.isNotBlank()) result.translationChrf
            else (if (source == "zh") result.characterErrorRate else result.wordErrorRate ?: result.characterErrorRate)?.let { 1.0 - it }
        val fastest = comparable.minByOrNull(::time)
        val scored = comparable.filter { quality(it)?.isFinite() == true }
        val accurate = scored.maxByOrNull { quality(it)!! }
        val balanced = if (scored.size < 3) null else {
            val bySpeed = scored.sortedBy(::time).mapIndexed { index, result -> result.identity to index }.toMap()
            val byQuality = scored.sortedByDescending { quality(it) }.mapIndexed { index, result -> result.identity to index }.toMap()
            scored.minWithOrNull(compareBy<BenchmarkResult> {
                maxOf(bySpeed.getValue(it.identity), byQuality.getValue(it.identity))
            }.thenBy { bySpeed.getValue(it.identity) + byQuality.getValue(it.identity) }
                .thenBy(::time))
        }
        return BenchmarkLeaders(comparable, fastest, balanced, accurate)
    }
}
