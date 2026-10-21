package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Overlay. The scene is the page: everything on the right changes it in place, and
 * the two facts under it describe where the window goes and how it is toggled.
 */
@Composable
internal fun OverlayScreen(
    state: OverlayState,
    actions: OverlayActions,
    modifier: Modifier = Modifier,
) {
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = OverlayCopy.EYEBROW,
            title = OverlayCopy.TITLE,
            lede = OverlayCopy.LEDE,
            actions = {
                HearthButton(
                    OverlayCopy.RESET,
                    actions.onReset,
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
                HearthButton(
                    text = if (state.settings.visible) OverlayCopy.HIDE else OverlayCopy.SHOW,
                    onClick = { actions.onToggleVisible(!state.settings.visible) },
                    tone = ButtonTone.Primary,
                    size = ButtonSize.Small,
                    icon = if (state.settings.visible) HearthIcons.Close else HearthIcons.Overlay,
                )
            },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f)) {
                OverlayScene(state)
                Spacer(Modifier.height(14.dp))
                OverlayFacts(state)
            }
            Column(
                Modifier.width(Space.sideColumn),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OverlayPlacementCard(
                    settings = state.settings,
                    widthPercentLabel = state.widthPercentLabel,
                    backdropPercentLabel = state.backdropPercentLabel,
                    onChange = actions.onChange,
                )
                OverlayDisplayCard(settings = state.settings, onChange = actions.onChange)
                OverlayStyleCard(settings = state.settings, onChange = actions.onChange)
            }
        }
    }
}

