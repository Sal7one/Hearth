package com.sal7one.hearth.desktop.ui.screens.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Typed translation. The draft is local; the result is the last text session the
 * controller actually ran, so an empty result means nothing has been translated yet.
 */
@Composable
internal fun TranslateScreen(
    state: TranslateState,
    onTranslate: (String) -> Unit,
    onSwapLanguages: () -> Unit,
    onCopyResult: () -> Unit,
    onOpenVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    var draft by remember { mutableStateOf("") }

    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = TranslateCopy.EYEBROW,
            title = TranslateCopy.TITLE,
            lede = TranslateCopy.LEDE,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(state.sourceLabel, tone = ChipTone.Neutral)
                    HearthButton("", onSwapLanguages, tone = ButtonTone.Ghost, size = ButtonSize.Small, icon = HearthIcons.Swap)
                    Chip(state.targetLabel, tone = ChipTone.Neutral)
                }
            },
        )

        state.error?.let { message ->
            Banner(message, tone = BannerTone.Err)
            Spacer(Modifier.height(14.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HearthCard(Modifier.weight(1f), padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(TranslateCopy.INPUT, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                        Text(
                            "${draft.length} of ${TranslateCopy.MAX_CHARS} characters",
                            style = HearthType.small,
                            color = colors.muted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = draft,
                    onValueChange = { if (it.length <= TranslateCopy.MAX_CHARS) draft = it },
                    textStyle = HearthType.body.copy(color = colors.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp)
                        .background(colors.surface2, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(TranslateCopy.PLACEHOLDER, style = HearthType.small, color = colors.faint)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        if (state.translationEnabled) "Translation on" else "Translation off",
                        tone = if (state.translationEnabled) ChipTone.Ok else ChipTone.Warn,
                    )
                    Spacer(Modifier.weight(1f))
                    HearthButton(
                        TranslateCopy.TRANSLATE,
                        onClick = { onTranslate(draft) },
                        tone = ButtonTone.Primary,
                        icon = HearthIcons.ArrowRight,
                        iconAfter = true,
                        enabled = draft.isNotBlank() && !state.busy,
                    )
                }
            }

            HearthCard(Modifier.weight(1f), padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.targetLabel,
                        style = HearthType.h3.copy(fontSize = 15.sp),
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Chip(
                        if (state.busy) "Working" else "Last result",
                        tone = if (state.busy) ChipTone.Info else ChipTone.Neutral,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 260.dp)) {
                    if (state.hasResult) {
                        Text(
                            state.translated.ifBlank { state.original },
                            style = HearthType.h2.copy(fontSize = 20.sp, lineHeight = 30.sp),
                            color = colors.text,
                        )
                    } else {
                        Text(TranslateCopy.EMPTY, style = HearthType.small, color = colors.faint)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HearthButton(
                        TranslateCopy.COPY,
                        onCopyResult,
                        tone = ButtonTone.Secondary,
                        size = ButtonSize.Small,
                        enabled = state.translated.isNotBlank(),
                    )
                    HearthButton(
                        "Voice settings",
                        onOpenVoice,
                        tone = ButtonTone.Ghost,
                        size = ButtonSize.Small,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Banner(TranslateCopy.LISTEN_NOTE, tone = BannerTone.Neutral)
    }
}
