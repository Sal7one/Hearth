package com.sal7one.transiber.benchmark

import java.util.Locale

/** The live inference ceiling applies to one complete input, independently of corpus size. */
internal object BenchmarkAttemptPolicy {
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 20
    const val DEFAULT_SECONDS = 20
    const val PROTOCOL_VERSION = 4

    /** Older, imported or edited preferences may never disable the safety ceiling. */
    fun seconds(value: Int): Int = value.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun timeoutMillis(value: Int): Long = seconds(value) * 1_000L

    /**
     * A timed-out artifact/backend is not retried for each recording language or decoder hint.
     * Keep other requested backends eligible: a CPU timeout says nothing about the GPU route.
     * Voice styles share the model's budget; a different family or changed artifact gets its own.
     */
    fun disqualificationKey(candidate: BenchmarkCandidate): String {
        val artifact = candidate.artifactFingerprint?.takeIf(String::isNotBlank)
            ?: candidate.file?.absolutePath
            ?: candidate.id
        return benchmarkHash(listOf("benchmark-attempt-v1", candidate.task.name, artifact,
            candidate.acceleration?.lowercase(Locale.ROOT) ?: "cpu", candidate.voiceFamily.orEmpty())
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
    }

    fun timeoutMessage(value: Int): String =
        "Disqualified: one input exceeded ${seconds(value)} seconds of inference. Model preparation and other samples have separate limits."

    /** Interrupted work may remain diagnostic metadata, but it cannot earn a speed or quality score. */
    fun failureResult(result: BenchmarkResult, message: String): BenchmarkResult {
        require(message.isNotBlank())
        return result.copy(error = message, computeMs = emptyList(), firstTextMs = emptyList(),
            texts = emptyList(), samples = emptyList(), audioMsByPass = emptyList(),
            wordErrorRate = null, characterErrorRate = null, translationChrf = null)
    }
}
