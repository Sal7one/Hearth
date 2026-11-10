package com.sal7one.hearth.desktop.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.core.models.ModelMeasurement
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

@Composable
internal fun ModelMeasurements(measurements: List<ModelMeasurement>, showModel: Boolean = false) {
    val colors = HearthTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        measurements.forEach { measurement ->
            Text(
                buildAnnotatedString {
                    if (showModel) append("${measurement.title} · ")
                    withStyle(SpanStyle(color = colors.accent)) { append(measurement.label) }
                    append(" · ${measurement.context} · ")
                    withLink(LinkAnnotation.Url(measurement.sourceUrl,
                        TextLinkStyles(style = SpanStyle(color = colors.textDim, textDecoration = TextDecoration.Underline)))) {
                        append(measurement.sourceLabel)
                    }
                },
                style = HearthType.rowSub,
                color = colors.muted,
            )
        }
    }
}
