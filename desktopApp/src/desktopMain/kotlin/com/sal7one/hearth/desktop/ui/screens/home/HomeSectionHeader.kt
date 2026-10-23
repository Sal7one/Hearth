package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.Eyebrow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Section head inside a page: small eyebrow, a 30 px title, an optional sentence
 * and one trailing action. Used by the map and by every grouped list screen.
 */
@Composable
internal fun HomeSectionHeader(
    eyebrow: String,
    title: String,
    lede: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = HearthTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(8.dp))
            Text(title, style = HearthType.h1, color = colors.text)
            if (lede != null) {
                Spacer(Modifier.height(9.dp))
                Text(
                    lede,
                    style = HearthType.lede,
                    color = colors.textDim,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(20.dp))
            action()
        }
    }
}
