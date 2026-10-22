package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * One transcript entry. The translation is the largest text on the page because it
 * is what the operator is reading; the source line stays available, smaller.
 *
 * A partial line never renders as finished: it keeps its source text, loses the
 * right to look final, and carries an explicit Listening marker.
 */
@Composable
internal fun TranscriptLine(
    line: CaptionLine,
    rtl: Boolean,
    translationEnabled: Boolean,
    showOriginal: Boolean,
    showPendingOriginal: Boolean,
    modifier: Modifier = Modifier,
    translationFailed: Boolean = false,
) {
    val colors = HearthTheme.colors
    val translation = line.translation?.takeIf { it.isNotBlank() }
    val primary = when {
        translation != null -> translation
        translationEnabled && (!showPendingOriginal || translationFailed) -> null
        else -> line.original
    }
    val secondary = line.original.takeIf { (translation != null || translationFailed) && showOriginal }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.timeLabel, style = HearthType.monoSmall, color = colors.faint)
            if (line.partial) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(CaptionsCopy.PARTIAL, style = HearthType.rowSub, color = colors.accent)
            }
        }
        Spacer(Modifier.height(5.dp))
        if (secondary != null) {
            Text(
                secondary,
                style = HearthType.transcriptOriginal,
                color = colors.textDim,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        if (primary != null) {
            Text(
                text = primary,
                style = if (rtl) {
                    HearthType.transcriptTranslation.copy(textDirection = TextDirection.Rtl, textAlign = TextAlign.Right)
                } else {
                    HearthType.transcriptTranslation
                },
                color = colors.text,
            )
        } else {
            Text(
                if (translationFailed) "Translation unavailable" else CaptionsCopy.WAITING_TRANSLATION,
                style = HearthType.small,
                color = colors.muted,
            )
        }
    }
}
