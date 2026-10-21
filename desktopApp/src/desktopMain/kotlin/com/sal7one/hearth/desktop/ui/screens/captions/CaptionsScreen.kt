package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthSwitch
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * The workspace. Fixed header, a transcript that owns its own scroll, a narrow side
 * column, and a docked control bar. The page itself never scrolls, so a running
 * session cannot scroll its own controls out of reach.
 */
@Composable
internal fun CaptionsScreen(
    state: CaptionsState,
    actions: CaptionsActions,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    fixedClock: String? = null,
) {
    val colors = HearthTheme.colors
    val liveClock = rememberSessionClock(state.active)
    val clock = fixedClock ?: liveClock

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = Space.maxWidth)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = Space.pageX)
                .padding(top = Space.pageTop),
        ) {
            PageHeader(
                eyebrow = if (state.microphone) "Session · microphone" else CaptionsCopy.EYEBROW,
                title = CaptionsCopy.TITLE,
                lede = if (state.speechReady) CaptionsCopy.LEDE else CaptionsCopy.LEDE_SETUP,
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(CaptionsCopy.FLOATING, style = HearthType.small, color = colors.textDim)
                        HearthSwitch(state.overlayVisible, actions.onToggleOverlay)
                        HearthButton(
                            CaptionsCopy.SAVE,
                            actions.onSaveTranscript,
                            tone = ButtonTone.Secondary,
                            size = ButtonSize.Small,
                            enabled = state.hasFinalTranscript,
                        )
                    }
                },
            )

            if (state.error != null) {
                Banner(
                    text = state.error,
                    tone = BannerTone.Err,
                    action = { HearthButton("Dismiss", actions.onRetry, tone = ButtonTone.Ghost, size = ButtonSize.Small) },
                )
                Spacer(Modifier.height(14.dp))
            } else if (!state.speechReady) {
                Banner(
                    text = "No speech engine is selected. Captions need a recognition stage before anything else can run.",
                    tone = BannerTone.Warn,
                    action = { HearthButton("Choose a model", onOpenModels, tone = ButtonTone.Secondary, size = ButtonSize.Small) },
                )
                Spacer(Modifier.height(14.dp))
            } else if (state.notice != null) {
                Banner(text = state.notice, tone = BannerTone.Ok)
                Spacer(Modifier.height(14.dp))
            }
        }

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = Space.maxWidth)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = Space.pageX, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            TranscriptStage(
                state = state,
                clock = clock,
                onFinishMicrophone = actions.onFinishMicrophone,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SessionSidebar(
                state = state,
                actions = actions,
                modifier = Modifier.width(336.dp).fillMaxHeight(),
            )
        }

        Box(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            CallBar(state, actions)
        }
    }
}
