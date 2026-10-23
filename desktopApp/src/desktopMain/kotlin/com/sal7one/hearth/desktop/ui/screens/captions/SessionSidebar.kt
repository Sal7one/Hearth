package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The narrow column: what the floating window shows, what the three stages are,
 * and what can be taken away from the session. Never more than three cards — the
 * transcript is the page.
 */
@Composable
internal fun SessionSidebar(
    state: CaptionsState,
    actions: CaptionsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FloatingCaptionsCard(
            state = state,
            onToggleOverlay = actions.onToggleOverlay,
            onShowOriginal = actions.onShowOriginal,
            onShowPendingOriginal = actions.onShowPendingOriginal,
        )
        StagesCard(
            state = state,
            onManage = actions.onOpenStages,
            onChangeStage = actions.onChangeStage,
        )
        OutputCard(
            state = state,
            onCopyTranscript = actions.onCopyTranscript,
            onCopyTranslation = actions.onCopyTranslation,
            onSaveTranscript = actions.onSaveTranscript,
            onPlaySpeech = actions.onPlaySpeech,
            onStopSpeech = actions.onStopSpeech,
        )
    }
}
