package com.sal7one.hearth.desktop.ui.screens.captions

/**
 * One rendered caption. `partial` is the difference between a line the engine is
 * still revising and a line that is done — the UI never treats them the same.
 */
internal data class CaptionLine(
    val id: String,
    val ordinal: Int,
    val timeLabel: String,
    val original: String,
    val translation: String?,
    val partial: Boolean,
)

/**
 * Everything the workspace renders, resolved from the controller before
 * composition. No field is derived in a composable, so the screen is a pure
 * function of this state and can be rendered from a fixture.
 */
internal data class CaptionsState(
    val sourceLabel: String = "English",
    val targetLabel: String = "العربية",
    val sourceCode: String = "en",
    val targetLanguage: String = "ar",
    val speechEngine: String? = null,
    val translationEngine: String? = null,
    val voiceEngine: String? = null,
    val speechReady: Boolean = false,
    val translationEnabled: Boolean = false,
    val voiceEnabled: Boolean = false,
    val active: Boolean = false,
    val starting: Boolean = false,
    val cancelling: Boolean = false,
    val finishing: Boolean = false,
    val microphone: Boolean = false,
    val playingAudio: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lines: List<CaptionLine> = emptyList(),
    val audioPath: String? = null,
    val showOriginal: Boolean = true,
    val showPendingOriginal: Boolean = true,
    val overlayVisible: Boolean = false,
    val deviceName: String? = null,
    val inputLabel: String = "Microphone",
) {
    val hasFinalTranscript: Boolean get() = lines.any { !it.partial && it.original.isNotBlank() }
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    val livePartial: CaptionLine? get() = lines.lastOrNull { it.partial }
    val busy: Boolean get() = starting || cancelling

    /** Codes are shown, never derived from a display name ("العربية" has no "AR" in it). */
    val sourceCodeLabel: String get() = sourceCode.take(2).uppercase()
    val targetCodeLabel: String get() = targetLanguage.take(2).uppercase()

    /** Translation lines are laid out right-to-left for these languages. */
    val targetRtl: Boolean
        get() = targetLanguage.lowercase().substringBefore('-') in RTL_LANGUAGES

    val statusLabel: String
        get() = when {
            starting -> "Starting"
            cancelling -> "Stopping"
            finishing -> "Finishing"
            active -> "Listening"
            error != null -> "Needs attention"
            speechReady -> "Ready"
            else -> "Not configured"
        }
}

private val RTL_LANGUAGES = setOf("ar", "he", "fa", "ur", "ps", "sd", "ug", "yi", "dv", "ku", "ckb")

internal fun formatCaptionTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
