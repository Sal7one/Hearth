package com.sal7one.transiber.benchmark

import java.util.Locale

internal enum class BenchmarkTableSort { WARM, AUDIO_SPEED, LOAD, QUALITY, FIRST_TEXT, SIZE, MODEL, RECENT }
internal enum class BenchmarkResultStatus { ALL, COMPLETED, DISQUALIFIED, FAILED, EMPTY }

/** Different recordings, phones, language pairs and scoring protocols must never share a winner. */
internal data class BenchmarkCohort(
    val task: String, val source: String, val target: String, val inputHash: String, val device: String,
    val protocolVersion: Int, val scoringNormalization: String?,
)

internal object BenchmarkTableModel {
    fun cohort(result: BenchmarkResult) = BenchmarkCohort(result.task, result.source, result.target, result.inputHash,
        result.device, result.protocolVersion, result.scoringNormalization)

    fun latestCohort(rows: List<BenchmarkResult>, source: String, target: String): BenchmarkCohort? =
        rows.asSequence().filter { it.source == source && it.target == target }
            .maxByOrNull { it.timestamp }?.let(::cohort)

    fun filtered(
        rows: List<BenchmarkResult>, query: String = "", status: BenchmarkResultStatus = BenchmarkResultStatus.ALL,
        cohort: BenchmarkCohort? = null,
    ): List<BenchmarkResult> {
        val terms = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return rows.filter { row ->
            (cohort == null || BenchmarkTableModel.cohort(row) == cohort) && when (status) {
                BenchmarkResultStatus.ALL -> true
                BenchmarkResultStatus.COMPLETED -> completed(row)
                BenchmarkResultStatus.DISQUALIFIED -> disqualified(row)
                BenchmarkResultStatus.FAILED -> row.error != null && !disqualified(row)
                BenchmarkResultStatus.EMPTY -> row.error == null && !disqualified(row) && row.texts.lastOrNull().isNullOrBlank()
            } && (terms.isEmpty() || listOf(row.model, row.identity, row.route, row.runtime, row.device,
                row.source, row.target, row.error.orEmpty(), row.inputHash, row.requestedAcceleration.orEmpty(), row.actualAcceleration.orEmpty(),
                row.languageControl.orEmpty(), row.recognitionLanguage.orEmpty(),
                row.terminationReason.orEmpty(), if (disqualified(row)) "disqualified" else "",
                if (row.languageControl == "forced") "language hint" else "").joinToString(" ")
                .lowercase(Locale.ROOT).let { haystack -> terms.all(haystack::contains) })
        }
    }

    fun disqualified(result: BenchmarkResult): Boolean = when (result.terminationReason) {
        "deadline", "worker_crash", "disqualified" -> true
        else -> false
    }

    fun completed(result: BenchmarkResult): Boolean = result.error == null && !disqualified(result) && result.computeMs.size >= 3 &&
        result.computeMs.all { it.isFinite() && it >= 0 } && result.texts.lastOrNull()?.isNotBlank() == true

    /** All history remains inside one task; the language matrix uses its latest exact cohort per pair. */
    fun scopedRows(
        rows: List<BenchmarkResult>, task: String, source: String, target: String,
        allInputs: Boolean = false, allLanguages: Boolean = false, cohort: BenchmarkCohort? = null,
    ): List<BenchmarkResult> {
        val taskRows = rows.filter { it.task == task }
        if (allInputs) return taskRows
        if (allLanguages) return taskRows.groupBy { it.source to it.target }.values.flatMap { pairRows ->
            val latest = pairRows.maxByOrNull { it.timestamp }?.let(BenchmarkTableModel::cohort)
            pairRows.filter { BenchmarkTableModel.cohort(it) == latest }
                .sortedByDescending { it.timestamp }.distinctBy { it.identity }
        }
        return taskRows.filter { it.source == source && it.target == target &&
            (cohort == null || BenchmarkTableModel.cohort(it) == cohort) }
    }

    /**
     * Reference agreement, never a blend of accuracy and speed or a listening score.
     * A stored metric alone is insufficient without its reference and a completed run.
     */
    fun referenceScore100(result: BenchmarkResult): Double? {
        if (result.task == "voice" || !completed(result) ||
            (result.referenceText.isNullOrBlank() && result.samples.none { !it.silence && !it.reference.isNullOrBlank() })) return null
        val translation = result.target.isNotBlank()
        val value = (if (translation) result.translationChrf
            else if (result.source.substringBefore('-').equals("zh", ignoreCase = true)) result.characterErrorRate
            else result.wordErrorRate)?.takeIf { it.isFinite() && it >= 0 } ?: return null
        if (translation && value > 1) return null
        return (if (translation) value * 100 else (1.0 - value) * 100).coerceIn(0.0, 100.0)
    }

    /** Paired warmed generated/replayed audio seconds per compute second; not live caption latency. */
    fun audioSpeed(result: BenchmarkResult): Double? {
        if (result.task == "voice") {
            val times = result.computeMs.drop(1)
            val durations = result.audioMsByPass.drop(1)
            if (result.error != null || disqualified(result) || times.isEmpty() || times.size != durations.size ||
                times.any { !it.isFinite() || it <= 0 } || durations.any { it <= 0 }) return null
            return BenchmarkMetrics.median(times.zip(durations) { time, duration -> duration / time })
        }
        return warmMs(result)?.takeIf { it > 0 && result.audioMs > 0 }?.let { result.audioMs / it }
    }

    fun translationMsPerSentence(result: BenchmarkResult): Double? = warmMs(result)?.takeIf { result.target.isNotBlank() }
        ?.let { total -> result.samples.count { !it.silence }.takeIf { it > 0 }?.let { total / it } }

    fun warmMs(result: BenchmarkResult): Double? = result.computeMs.drop(1)
        .takeIf { result.error == null && !disqualified(result) && it.isNotEmpty() && it.all { time -> time.isFinite() && time >= 0 } }
        ?.let(BenchmarkMetrics::median)

    /** Includes final-result fallback for engines that do not stream; the detail view labels this. */
    fun firstTextMs(result: BenchmarkResult): Double? = result.firstTextMs.drop(1)
        .takeIf { result.error == null && !disqualified(result) && it.isNotEmpty() && it.all { time -> time.isFinite() && time >= 0 } }
        ?.let(BenchmarkMetrics::median)

    fun qualityValue(result: BenchmarkResult): Double? = (if (result.task == "voice") {
        null
    } else if (result.target.isNotBlank()) {
        result.translationChrf
    } else if (result.source == "zh" || result.wordErrorRate == null) {
        result.characterErrorRate
    } else {
        result.wordErrorRate
    })?.takeIf { result.error == null && !disqualified(result) && it.isFinite() }

    /** Higher is better. Speech error rates are inverted only for ranking. */
    fun qualityScore(result: BenchmarkResult): Double? = qualityValue(result)?.let { value ->
        if (result.target.isNotBlank()) value else 1.0 - value
    }

    /** Requested placement is an experiment setting, never proof that a GPU was used. */
    fun requestedBackend(result: BenchmarkResult): String? = result.requestedAcceleration ?: result.identity.substringBefore("#language=").substringAfterLast('@', "")
        .takeIf { it in setOf("cpu", "vulkan", "opencl", "metal", "cuda", "auto") }

    /** Historical recording language is not evidence of the recognizer's decoder setting. */
    fun recognitionSetting(result: BenchmarkResult): BenchmarkRecognitionSetting? {
        val language = result.recognitionLanguage?.lowercase(Locale.ROOT) ?: return null
        val code = language.matches(Regex("[a-z]{2,3}")) && language != "auto"
        return when (result.languageControl) {
            "forced" -> BenchmarkRecognitionSetting("forced", language).takeIf { code }
            "automatic" -> BenchmarkRecognitionSetting("automatic", language).takeIf { language == "auto" }
            "fixed" -> BenchmarkRecognitionSetting("fixed", language).takeIf { code || language == "model" }
            else -> null
        }
    }

    fun sorted(rows: List<BenchmarkResult>, sort: BenchmarkTableSort): List<BenchmarkResult> = when (sort) {
        BenchmarkTableSort.WARM -> rows.sortedWith(compareBy<BenchmarkResult> {
            warmMs(it) ?: Double.POSITIVE_INFINITY
        }.thenBy { it.model })
        BenchmarkTableSort.AUDIO_SPEED -> rows.sortedWith(compareByDescending<BenchmarkResult> {
            audioSpeed(it) ?: Double.NEGATIVE_INFINITY
        }.thenBy { it.model })
        BenchmarkTableSort.LOAD -> rows.sortedWith(compareBy<BenchmarkResult> {
            it.loadMs.takeIf { ms -> it.error == null && !disqualified(it) && ms.isFinite() && ms >= 0 } ?: Double.POSITIVE_INFINITY
        }.thenBy { it.model })
        BenchmarkTableSort.QUALITY -> rows.sortedWith(compareByDescending<BenchmarkResult> {
            referenceScore100(it) ?: Double.NEGATIVE_INFINITY
        }.thenBy { warmMs(it) ?: Double.POSITIVE_INFINITY })
        BenchmarkTableSort.FIRST_TEXT -> rows.sortedWith(compareBy<BenchmarkResult> {
            firstTextMs(it) ?: Double.POSITIVE_INFINITY
        }.thenBy { it.model })
        BenchmarkTableSort.SIZE -> rows.sortedWith(compareBy<BenchmarkResult> {
            it.modelBytes?.takeIf { bytes -> bytes >= 0 } ?: Long.MAX_VALUE
        }.thenBy { it.model })
        BenchmarkTableSort.MODEL -> rows.sortedWith(compareBy<BenchmarkResult> { it.model.lowercase(Locale.ROOT) }
            .thenByDescending { it.timestamp })
        BenchmarkTableSort.RECENT -> rows.sortedByDescending { it.timestamp }
    }
}
