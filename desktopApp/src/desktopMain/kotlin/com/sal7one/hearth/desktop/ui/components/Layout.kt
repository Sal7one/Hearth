package com.sal7one.hearth.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Base surface: one border, one radius, no decoration. */
@Composable
internal fun HearthCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HearthTheme.colors
    val shapes = HearthTheme.shapes
    Column(
        modifier = modifier
            .shadow(if (colors.isDark) 0.dp else 10.dp, shapes.card, clip = false)
            .clip(shapes.card)
            .background(colors.surface)
            .border(1.dp, colors.border, shapes.card)
            .padding(padding),
        content = content,
    )
}

/** A card with the standard title / detail / action head. */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    HearthCard(modifier, padding) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = HearthType.h3, color = HearthTheme.colors.text)
                if (detail != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(detail, style = HearthType.small, color = HearthTheme.colors.muted)
                }
            }
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                action()
            }
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** Uppercase tracked label that opens a block. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = HearthType.micro,
        color = HearthTheme.colors.muted,
        maxLines = 1,
    )
}

/** Page title block. Actions sit on the baseline of the title, not floating above it. */
@Composable
internal fun PageHeader(
    eyebrow: String,
    title: String,
    lede: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = HearthTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 26.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
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
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
        if (actions != null) {
            Spacer(Modifier.width(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

/** Two-column content: main surface plus a narrow side column. */
@Composable
internal fun SplitLayout(
    modifier: Modifier = Modifier,
    side: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        Column(Modifier.width(336.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = side)
    }
}

/** Key/value block used by every status card. */
@Composable
internal fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Row(
        modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = HearthType.rowSub, color = colors.muted, modifier = Modifier.weight(1f))
        Text(
            value,
            style = HearthType.rowSub.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
