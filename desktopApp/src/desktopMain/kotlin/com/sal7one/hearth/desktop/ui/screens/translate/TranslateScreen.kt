package com.sal7one.hearth.desktop.ui.screens.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.shell.PageScaffold

/**
 * Typed translation. The draft is local; the result is the last text session the
 * controller actually ran, so an empty result means nothing has been translated yet.
 */
@Composable
internal fun TranslateScreen(
    state: TranslateState,
    actions: TranslateActions,
    modifier: Modifier = Modifier,
) {
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = TranslateCopy.EYEBROW,
            title = TranslateCopy.TITLE,
            lede = TranslateCopy.LEDE,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(state.sourceLabel, tone = ChipTone.Neutral)
                    HearthButton("", actions.onSwapLanguages, tone = ButtonTone.Ghost, size = ButtonSize.Small, icon = HearthIcons.Swap)
                    Chip(state.targetLabel, tone = ChipTone.Neutral)
                }
            },
        )

        state.error?.let { message ->
            Banner(message, tone = BannerTone.Err)
            Spacer(Modifier.height(14.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TranslateInputCard(state, actions.onTranslate, Modifier.weight(1f))
            TranslateResultCard(state, actions, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        Banner(TranslateCopy.LISTEN_NOTE, tone = BannerTone.Neutral)
    }
}
