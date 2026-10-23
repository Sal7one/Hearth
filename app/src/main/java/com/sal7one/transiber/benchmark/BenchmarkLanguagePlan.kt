package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.speech.SpeechLanguage

internal enum class BenchmarkLanguageMode { MATCH_INPUT, AUTOMATIC, BOTH }

internal data class BenchmarkLanguageExpansion(val models: List<BenchmarkCandidate>, val omitted: List<String>)

/** Describes the recognizer's language control, separately from the recording's scoring language. */
internal data class BenchmarkRecognitionSetting(val control: String, val language: String)

internal object BenchmarkLanguagePlan {
    /** Only an exact installed-tree pin establishes a legacy Vosk model's fixed language. */
    fun voskLanguages(treeSha256: String?): Set<String> =
        com.sal7one.transiber.models.VoskDownloads.findByTreeSha256(treeSha256)?.let { setOf(it.language) } ?: setOf("model")

    fun expand(models: List<BenchmarkCandidate>, sampleLanguage: String, mode: BenchmarkLanguageMode): BenchmarkLanguageExpansion {
        val source = SpeechLanguage.normalize(sampleLanguage)
        require(source.matches(Regex("[a-z]{2,3}")) && source !in setOf("auto", "model")) {
            "Choose the recording's actual language before comparing recognition language controls"
        }
        val omitted = mutableListOf<String>()
        val planned = models.flatMap { candidate ->
            if (candidate.task != BenchmarkTask.SPEECH) return@flatMap listOf(candidate)
            if (!supports(candidate, source)) {
                omitted += "${candidate.label}: does not support recordings in $source"
                return@flatMap emptyList()
            }
            val fixed = fixedLanguage(candidate)
            if (fixed != null) return@flatMap listOf(candidate.copy(recognitionLanguage = fixed))
            val hint = canHint(candidate, source)
            val automatic = canDetect(candidate)
            val languages = when (mode) {
                BenchmarkLanguageMode.MATCH_INPUT -> when { hint -> listOf(source); automatic -> listOf("auto"); else -> emptyList() }
                BenchmarkLanguageMode.AUTOMATIC -> if (automatic) listOf("auto") else emptyList()
                BenchmarkLanguageMode.BOTH -> buildList { if (hint) add(source); if (automatic) add("auto") }
            }
            if (languages.isEmpty()) omitted += "${candidate.label}: ${if (mode == BenchmarkLanguageMode.AUTOMATIC) "automatic language detection is unavailable" else "no supported recognition language control for $source"}"
            languages.map { candidate.copy(recognitionLanguage = it) }
        }.distinctBy(BenchmarkAccelerationPlan::identity)
        return BenchmarkLanguageExpansion(planned, omitted.distinct())
    }

    /** Null overrides preserve the previous execution options while reporting actual capability.
     * Explicit overrides, produced by [expand], must be executable by the selected model. */
    fun recognition(candidate: BenchmarkCandidate, sampleLanguage: String): BenchmarkRecognitionSetting? {
        if (candidate.task != BenchmarkTask.SPEECH) return null
        val source = SpeechLanguage.normalize(sampleLanguage)
        require(supports(candidate, source)) { "${candidate.label} does not support recordings in $source" }
        val requested = candidate.recognitionLanguage?.let(SpeechLanguage::normalize)
        val fixed = fixedLanguage(candidate)
        if (fixed != null) {
            require(requested == null || requested == fixed) { "${candidate.label} has a fixed recognition language: $fixed" }
            return BenchmarkRecognitionSetting("fixed", fixed)
        }
        val selection = requested ?: source
        if (selection == "auto") {
            require(canDetect(candidate)) { "${candidate.label} does not support automatic language detection" }
            return BenchmarkRecognitionSetting("automatic", "auto")
        }
        if (canHint(candidate, selection)) return BenchmarkRecognitionSetting("forced", selection)
        require(requested == null && canDetect(candidate)) {
            "${candidate.label} cannot enforce recognition language $selection"
        }
        return BenchmarkRecognitionSetting("automatic", "auto")
    }

    private fun supports(candidate: BenchmarkCandidate, source: String) =
        source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")

    private fun fixedLanguage(candidate: BenchmarkCandidate): String? {
        candidate.profile?.let { profile ->
            return profile.fixedSourceLanguage ?: profile.capabilities.sourceLanguages.singleOrNull()
        }
        if (candidate.route.startsWith("Vosk /")) return candidate.sourceCodes.singleOrNull()?.takeUnless { it in setOf("auto", "model") } ?: "model"
        if (candidate.sourceCodes == setOf("model")) return "model"
        if (candidate.route.startsWith("Whisper /")) return candidate.sourceCodes.filterNot { it == "auto" }.singleOrNull()
        return null
    }

    private fun canHint(candidate: BenchmarkCandidate, language: String): Boolean = candidate.profile?.let {
        language in it.capabilities.sourceLanguageHints
    } ?: (candidate.route.startsWith("Whisper /") && language != "auto" && language in candidate.sourceCodes)

    private fun canDetect(candidate: BenchmarkCandidate): Boolean = candidate.profile?.capabilities?.automaticSourceLanguage
        ?: (candidate.route.startsWith("Whisper /") && "auto" in candidate.sourceCodes)
}
