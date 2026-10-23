package com.sal7one.hearth.desktop.ui.screens.captions

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.DesktopUiState
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.home.languageName
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.language.*

/**
 * Maps controller state onto the workspace. A partial line stays partial; a
 * translation that has not arrived stays null so the UI can say "Translating…"
 * instead of showing the original twice.
 */
internal fun captionsState(
    workspace: DesktopUiState,
    config: PipelineConfig,
    capabilities: List<EngineDescriptor>,
    showOriginal: Boolean,
    showPendingOriginal: Boolean,
    overlayVisible: Boolean,
    deviceName: String?,
    inputLabel: String,
    catalog: List<DesktopModelCard> = emptyList(),
    installations: Map<String, DesktopModelStatus> = emptyMap(),
    systemAudioAvailable: Boolean = false,
    systemAudioUnavailableReason: String? = null,
): CaptionsState {
    fun descriptor(stage: PipelineStage): EngineDescriptor? {
        val id = config.selection(stage)?.engineId ?: return null
        return capabilities.firstOrNull { it.id == id }
    }

    val speech = descriptor(PipelineStage.STT)
    val translation = descriptor(PipelineStage.TRANSLATION)
    val voice = descriptor(PipelineStage.TTS)
    val snapshot = workspace.snapshot

    return CaptionsState(
        sourceLabel = languageName(config.sourceLanguage),
        targetLabel = languageName(config.targetLanguage),
        sourceCode = config.sourceLanguage,
        targetLanguage = config.targetLanguage,
        speechEngine = speech?.name,
        translationEngine = translation?.name,
        voiceEngine = voice?.name,
        speechReady = stageSetupIssue(config, PipelineStage.STT, capabilities, installations, catalog) == null,
        translationEnabled = config.translation != null,
        voiceEnabled = config.voice != null,
        active = workspace.active,
        starting = workspace.starting,
        cancelling = workspace.cancelling,
        finishing = workspace.finishing,
        microphone = workspace.microphone,
        playingAudio = workspace.playingAudio,
        error = workspace.error ?: snapshot?.error,
        notice = workspace.notice,
        lines = snapshot?.segments.orEmpty().mapIndexed(::captionLine),
        audioPath = snapshot?.audio?.lastOrNull()?.path?.takeIf { it.isNotBlank() },
        showOriginal = showOriginal,
        showPendingOriginal = showPendingOriginal,
        overlayVisible = overlayVisible,
        deviceName = deviceName,
        inputLabel = inputLabel,
        languages = pipelineLanguages(config, capabilities, catalog),
        readinessIssue = PipelineStage.entries.filter { it == PipelineStage.STT || config.selection(it) != null }
            .firstNotNullOfOrNull { stageSetupIssue(config, it, capabilities, installations, catalog) },
        systemAudioAvailable = systemAudioAvailable,
        systemAudioUnavailableReason = systemAudioUnavailableReason,
        systemAudio = workspace.systemAudio,
    )
}

private fun captionLine(index: Int, segment: CaptionSegment): CaptionLine = CaptionLine(
    id = segment.id,
    ordinal = index + 1,
    timeLabel = formatCaptionTime(segment.startMs),
    original = segment.original,
    translation = segment.translation?.takeIf { it.isNotBlank() },
    partial = !segment.final,
)
