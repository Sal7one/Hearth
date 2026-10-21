package com.sal7one.hearth.desktop.ui.screens.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The draft is local to this card; it is not persisted and not a session. */
@Composable
internal fun TranslateInputCard(
    state: TranslateState,
    onTranslate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    var draft by remember { mutableStateOf("") }

    HearthCard(modifier, padding = 18.dp) {
        Column(Modifier.fillMaxWidth()) {
            Text(TranslateCopy.INPUT, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
            Text(
                "${draft.length} of ${TranslateCopy.MAX_CHARS} characters",
                style = HearthType.small,
                color = colors.muted,
            )
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
}
