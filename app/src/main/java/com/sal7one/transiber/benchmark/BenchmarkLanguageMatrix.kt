package com.sal7one.transiber.benchmark

internal data class BenchmarkMatrixGroup(
    val route: BenchmarkRoute,
    val models: List<BenchmarkCandidate>,
    val cases: List<BenchmarkCase>,
)

internal data class BenchmarkMatrixPlan(
    val groups: List<BenchmarkMatrixGroup>,
    val skipped: List<String>,
) {
    /** A model tested in three languages contributes three comparisons. */
    val comparisonCount: Int get() = groups.sumOf { it.models.size }
}

/** Plans real reference comparisons; model language inventories never fabricate a test corpus. */
internal object BenchmarkLanguageMatrix {
    val languages = listOf("en", "ru", "zh", "ar")
    val defaultLanguages = setOf("en", "ru", "zh")
    private val hash = Regex("[a-f0-9]{64}")

    fun plan(
        candidates: List<BenchmarkCandidate>,
        suite: BenchmarkSuite,
        languages: Set<String>,
        task: BenchmarkTask,
        full: Boolean = false,
        translationTarget: String? = null,
    ): BenchmarkMatrixPlan {
        require(languages.all { it in this.languages }) { "Reference comparisons support only English, Russian, Chinese and Arabic" }
        if (task == BenchmarkTask.VOICE) return BenchmarkMatrixPlan(emptyList(),
            listOf("Voice synthesis has no speech/translation reference matrix. Choose a text and voice language instead."))
        if (languages.isEmpty()) return BenchmarkMatrixPlan(emptyList(), listOf("Choose at least one reference language."))
        if (candidates.isEmpty()) return BenchmarkMatrixPlan(emptyList(), listOf("Select at least one model to compare."))
        val skipped = mutableListOf<String>()
        val selectedModels = candidates.filter { candidate ->
            (candidate.task == task).also { matches ->
                if (!matches) skipped += "${candidate.id}: selected model belongs to ${candidate.task.name.lowercase()}, not ${task.name.lowercase()}."
            }
        }
        val selectedLanguages = this.languages.filter { it in languages }
        val routes = if (task == BenchmarkTask.SPEECH) selectedLanguages.map { BenchmarkRoute(it, "") }
            else selectedLanguages.flatMap { source -> selectedLanguages.filter { it != source &&
                (translationTarget == null || it == translationTarget) }.map { BenchmarkRoute(source, it) } }
        if (routes.isEmpty()) skipped += "No published direction matches the selected source and target languages."
        val groups = routes.mapNotNull { route ->
            val samples = suite.selected(task == BenchmarkTask.SPEECH, route.source, route.target, full)
            val references = samples.filterNot { it.silenceMs > 0 }
            val grounded = references.isNotEmpty() && references.all { sample ->
                sample.source == route.source && sample.referenceStatus == "publisher" && !sample.reference.isNullOrBlank() &&
                    if (task == BenchmarkTask.SPEECH) {
                        sample.audio != null && sample.target.isEmpty() && sample.sha256?.matches(hash) == true &&
                            sample.pcmSha256?.matches(hash) == true
                    } else !sample.speech && sample.target == route.target && sample.text.isNotBlank()
            }
            val label = if (route.target.isEmpty()) route.source else "${route.source} → ${route.target}"
            if (!grounded) {
                skipped += "$label: no complete publisher reference set in this suite; personal recordings are not substituted."
                return@mapNotNull null
            }
            val compatible = selectedModels.filter { candidate ->
                val sourceSupported = route.source in candidate.sourceCodes
                val directionSupported = task == BenchmarkTask.SPEECH ||
                    (candidate.translation?.supports(route.source, route.target)
                        ?: (route.target in candidate.targetCodes && route.source != route.target))
                (sourceSupported && directionSupported).also { supported ->
                    if (!supported) skipped += "${candidate.id} · $label: ${if (!sourceSupported)
                        "no explicit advertised source-language coverage (auto or model language is not proof of coverage)"
                    else "translation direction is not supported"}."
                }
            }
            if (compatible.isEmpty()) null else BenchmarkMatrixGroup(route, compatible.toList(), samples.toList())
        }
        return BenchmarkMatrixPlan(groups, skipped.toList())
    }
}
