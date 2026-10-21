package com.sal7one.hearth.desktop.ui.screens.translate

import com.sal7one.hearth.desktop.DesktopUiState
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.home.languageName
import com.sal7one.hearth.core.PipelineConfig

/** The typed-translation page. It reads the last text session, not a session of its own. */
internal data class TranslateState(
    val sourceLabel: String = "English",
    val targetLabel: String = "العربية",
    val translationEnabled: Boolean = false,
    val voiceEnabled: Boolean = false,
    val busy: Boolean = false,
    val original: String = "",
    val translated: String = "",
    val error: String? = null,
) {
    val hasResult: Boolean get() = translated.isNotBlank() || original.isNotBlank()
}

internal fun translateState(config: PipelineConfig, workspace: DesktopUiState): TranslateState {
    val snapshot = workspace.snapshot
    val original = snapshot?.segments.orEmpty().filter { it.final }.joinToString("\n") { it.original }.trim()
    val translated = snapshot?.segments.orEmpty().mapNotNull { it.translation?.takeIf(String::isNotBlank) }
        .joinToString("\n").trim()
    return TranslateState(
        sourceLabel = languageName(config.sourceLanguage),
        targetLabel = languageName(config.targetLanguage),
        translationEnabled = config.translation != null,
        voiceEnabled = config.voice != null,
        busy = workspace.starting || workspace.active,
        original = original,
        translated = translated,
        error = workspace.error ?: snapshot?.error,
    )
}
