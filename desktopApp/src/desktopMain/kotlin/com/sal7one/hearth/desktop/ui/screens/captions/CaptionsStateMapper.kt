package com.sal7one.hearth.desktop.ui.screens.captions

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.DesktopUiState
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.ui.screens.home.languageName

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
        speechReady = speech != null && speech.available,
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
