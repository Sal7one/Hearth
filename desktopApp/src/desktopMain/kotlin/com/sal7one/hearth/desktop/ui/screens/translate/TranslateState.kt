package com.sal7one.hearth.desktop.ui.screens.translate

import com.sal7one.hearth.desktop.DesktopUiState
import com.sal7one.hearth.desktop.WorkspaceInput
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.home.languageName
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.language.*

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
    val languages: PipelineLanguageState = PipelineLanguageState("en", "ar", emptyList(), emptyList(), false),
    val readinessIssue: String? = null,
    val translationEngine: String? = null,
    val resultTargetLabel: String = targetLabel,
    val otherSessionActive: Boolean = false,
) {
    val hasResult: Boolean get() = translated.isNotBlank()
    val ready: Boolean get() = translationEnabled && readinessIssue == null && languages.issue == null
}

internal fun translateState(config: PipelineConfig, workspace: DesktopUiState,
    capabilities: List<EngineDescriptor> = emptyList(), catalog: List<DesktopModelCard> = emptyList(),
    installations: Map<String, DesktopModelStatus> = emptyMap()): TranslateState {
    val snapshot = workspace.snapshot.takeIf { workspace.input == WorkspaceInput.TEXT }
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
        languages = pipelineLanguages(config, capabilities, catalog, typedText = true),
        readinessIssue = stageSetupIssue(config, PipelineStage.TRANSLATION, capabilities, installations, catalog)
            ?: if (config.voice != null) stageSetupIssue(config, PipelineStage.TTS, capabilities, installations, catalog) else null,
        translationEngine = capabilities.firstOrNull { it.id == config.translation?.engineId }?.name,
        resultTargetLabel = languageName(workspace.sessionConfig?.targetLanguage ?: config.targetLanguage),
        otherSessionActive = workspace.active && workspace.input != WorkspaceInput.TEXT,
    )
}
