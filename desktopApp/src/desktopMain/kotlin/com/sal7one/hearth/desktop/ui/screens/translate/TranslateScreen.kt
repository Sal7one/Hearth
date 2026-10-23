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
import com.sal7one.hearth.desktop.ui.language.LanguagePairPicker

/**
 * Typed translation. The draft is local; the result is the last text session the
 * controller actually ran, so an empty result means nothing has been translated yet.
 */
@Composable
internal fun TranslateScreen(
    state: TranslateState,
    draft: String = "",
    onDraftChange: (String) -> Unit = {},
    actions: TranslateActions,
    modifier: Modifier = Modifier,
) {
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = TranslateCopy.EYEBROW,
            title = TranslateCopy.TITLE,
            lede = TranslateCopy.LEDE,
            actions = {
                LanguagePairPicker(state.languages, actions.onSourceLanguage, actions.onTargetLanguage, actions.onSwapLanguages, enabled = !state.busy)
            },
        )

        state.error?.let { message ->
            Banner(message, tone = BannerTone.Err)
            Spacer(Modifier.height(14.dp))
        }
        if (state.otherSessionActive) {
            Banner("A live or file session is running. Finish it before translating typed text.", tone = BannerTone.Neutral,
                action = { HearthButton("Open live captions", actions.onOpenCaptions, tone = ButtonTone.Ghost, size = ButtonSize.Small) })
            Spacer(Modifier.height(14.dp))
        }
        state.readinessIssue?.let { message ->
            Banner(message, tone = BannerTone.Warn, action = {
                HearthButton("Finish setup", actions.onOpenSetup, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            })
            Spacer(Modifier.height(14.dp))
        }
        state.languages.issue?.let { message ->
            Banner(message, tone = BannerTone.Warn)
            Spacer(Modifier.height(14.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TranslateInputCard(state, draft, onDraftChange, actions.onTranslate, Modifier.weight(1f))
            TranslateResultCard(state, actions, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        Banner(if (state.voiceEnabled) "Speech is generated using your selected voice. Play it from the captions output when ready."
            else "Voice is optional. Translation works without setting up speech recognition or a voice.", tone = BannerTone.Neutral)
    }
}
