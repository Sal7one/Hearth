package com.sal7one.transiber.benchmark

/** Only routes with bundled references and an explicitly advertised model language are runnable. */
internal data class BenchmarkRoute(val source: String, val target: String)

internal object BenchmarkLanguageSweep {
    val languages = listOf("ar", "en", "ru", "zh")

    fun routes(candidate: BenchmarkCandidate, suite: BenchmarkSuite): List<BenchmarkRoute> =
        if (candidate.targetCodes.isEmpty()) languages.filter { it in candidate.sourceCodes }
            .map { BenchmarkRoute(it, "") }
            .filter { suite.selected(true, it.source, "", false).isNotEmpty() }
        else suite.cases.asSequence().filterNot(BenchmarkCase::speech)
            .map { BenchmarkRoute(it.source, it.target) }.distinct()
            .filter { it.source in languages && it.target in languages &&
                it.source in candidate.sourceCodes && it.target in candidate.targetCodes &&
                (candidate.translation?.supports(it.source, it.target) ?: true) &&
                suite.selected(false, it.source, it.target, false).isNotEmpty() }
            .toList()

    fun isBundled(result: BenchmarkResult, suite: BenchmarkSuite): Boolean {
        val expected = suite.selected(result.target.isBlank(), result.source, result.target, false).map(BenchmarkCase::id)
        return result.protocolVersion == 2 && expected.isNotEmpty() && result.samples.map(BenchmarkSampleResult::id) == expected
    }
}
