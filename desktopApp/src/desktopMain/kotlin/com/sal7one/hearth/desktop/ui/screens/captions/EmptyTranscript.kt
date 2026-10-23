package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Shown before the first line arrives. It says what will happen rather than
 * showing a grey box, and it never fabricates a sample caption.
 */
@Composable
internal fun EmptyTranscript(modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 340.dp)) {
            Text(
                CaptionsCopy.EMPTY_TITLE,
                style = HearthType.h3,
                color = colors.textDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                CaptionsCopy.EMPTY_DETAIL,
                style = HearthType.small,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
