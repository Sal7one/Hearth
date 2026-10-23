package com.sal7one.transiber.benchmark

/** A single reason for a disabled Run action, shared by every benchmark tab. */
internal enum class BenchmarkReadinessIssue {
    INITIALIZING, PREPARING_INPUT, ENGINE_IN_USE, NO_MODELS, UNKNOWN_SAMPLE_LANGUAGE,
    EMPTY_SAUDI_SET, NO_BUILT_IN_SAMPLES, NO_AUDIO, NO_TEXT, SAME_LANGUAGE_PAIR;

    val fix: BenchmarkReadinessFix get() = when (this) {
        ENGINE_IN_USE -> BenchmarkReadinessFix.STOP_ACTIVITY
        NO_MODELS -> BenchmarkReadinessFix.MODELS
        UNKNOWN_SAMPLE_LANGUAGE, EMPTY_SAUDI_SET, NO_BUILT_IN_SAMPLES, NO_AUDIO, NO_TEXT, SAME_LANGUAGE_PAIR -> BenchmarkReadinessFix.INPUT
        INITIALIZING, PREPARING_INPUT -> BenchmarkReadinessFix.NONE
    }
}

internal enum class BenchmarkReadinessFix { NONE, INPUT, MODELS, STOP_ACTIVITY }

/**
 * [dataLoading] means there is no usable discovery snapshot yet. Background refreshes
 * with a cached snapshot must pass false: checking another model cannot block Run.
 * Native ownership and integrity are still checked by the runner at execution time.
 */
internal fun benchmarkReadinessIssue(
    task: BenchmarkTask,
    selectedCount: Int,
    dataLoading: Boolean,
    preparingInput: Boolean,
    gateOwner: String?,
    running: Boolean,
    builtIn: Boolean,
    builtInCaseCount: Int,
    saudiSet: Boolean,
    customAudioPresent: Boolean,
    textPresent: Boolean,
    source: String,
    target: String,
): BenchmarkReadinessIssue? = when {
    // The foreground controller owns the action now; its Stop button replaces Run.
    running -> null
    dataLoading -> BenchmarkReadinessIssue.INITIALIZING
    preparingInput -> BenchmarkReadinessIssue.PREPARING_INPUT
    gateOwner != null -> BenchmarkReadinessIssue.ENGINE_IN_USE
    selectedCount <= 0 -> BenchmarkReadinessIssue.NO_MODELS
    task == BenchmarkTask.SPEECH && source in setOf("auto", "model", "") -> BenchmarkReadinessIssue.UNKNOWN_SAMPLE_LANGUAGE
    task == BenchmarkTask.SPEECH && builtIn && builtInCaseCount <= 0 && saudiSet && source == "ar" -> BenchmarkReadinessIssue.EMPTY_SAUDI_SET
    task != BenchmarkTask.VOICE && builtIn && builtInCaseCount <= 0 -> BenchmarkReadinessIssue.NO_BUILT_IN_SAMPLES
    task == BenchmarkTask.SPEECH && !builtIn && !customAudioPresent -> BenchmarkReadinessIssue.NO_AUDIO
    (task == BenchmarkTask.VOICE || task == BenchmarkTask.TRANSLATION && !builtIn) && !textPresent -> BenchmarkReadinessIssue.NO_TEXT
    task == BenchmarkTask.TRANSLATION && source == target -> BenchmarkReadinessIssue.SAME_LANGUAGE_PAIR
    else -> null
}
