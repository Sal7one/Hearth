package com.sal7one.hearth.desktop.ui.screens.captions

/**
 * All of the workspace's callbacks in one object. Passing twelve lambdas through
 * three levels of layout is how screens stop being reviewable — a screen takes a
 * state and an actions bundle, nothing else.
 */
internal class CaptionsActions(
    val onToggleOverlay: (Boolean) -> Unit = {},
    val onShowOriginal: (Boolean) -> Unit = {},
    val onShowPendingOriginal: (Boolean) -> Unit = {},
    val onOpenStages: () -> Unit = {},
    val onChangeStage: (StageSlot) -> Unit = {},
    val onCopyTranscript: () -> Unit = {},
    val onCopyTranslation: () -> Unit = {},
    val onSaveTranscript: () -> Unit = {},
    val onPlaySpeech: () -> Unit = {},
    val onStopSpeech: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onFinishMicrophone: () -> Unit = {},
    val onSwapLanguages: () -> Unit = {},
    val onToggleTranslate: () -> Unit = {},
    val onToggleVoice: () -> Unit = {},
    val onPickMicrophone: () -> Unit = {},
    val onPickAudioFile: () -> Unit = {},
    val onPickText: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSourceLanguage: (String) -> Unit = {},
    val onTargetLanguage: (String) -> Unit = {},
    val onOpenOverlay: () -> Unit = {},
    val onPickSystemAudio: () -> Unit = {},
)
