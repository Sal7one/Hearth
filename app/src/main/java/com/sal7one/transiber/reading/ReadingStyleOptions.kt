package com.sal7one.transiber.reading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.ui.components.HearthColourPicker
import java.util.Locale
import kotlin.math.roundToInt

/** Setup and floating controls edit the same saved appearance, using the same visual colour picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReadingStyleOptions(settings: ReadingOverlaySettings, onChange: ((ReadingOverlaySettings) -> ReadingOverlaySettings) -> Unit) {
    val text = rememberUiText()
    var editingColour by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text(R.string.reading_overlay_appearance), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text(R.string.reading_style_replace), style = MaterialTheme.typography.bodyLarge)
                Text(text(R.string.reading_style_replace_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(settings.replaceText, { value -> onChange { it.copy(replaceText = value) } },
                modifier = Modifier.semantics { contentDescription = text(R.string.reading_style_replace) })
        }
        if (settings.replaceText) Text(text(R.string.reading_style_opaque_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text(R.string.reading_style_saved_palette), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(ReadingBoxPreset.LIGHT to R.string.reading_style_light, ReadingBoxPreset.DARK to R.string.reading_style_dark,
                ReadingBoxPreset.WARM to R.string.reading_style_warm, ReadingBoxPreset.HIGH_CONTRAST to R.string.reading_style_contrast).forEach { (preset, label) ->
                FilterChip(preset.matches(settings), { onChange { preset.applyTo(it) } }, label = { Text(text(label)) })
            }
        }
        ReadingStyleSlider(text(R.string.reading_style_width), settings.boxWidthPercent, 50f..200f, 29) { value -> onChange { it.copy(boxWidthPercent = value) } }
        ReadingStyleSlider(text(R.string.reading_style_height), settings.boxHeightPercent, 50f..300f, 49) { value -> onChange { it.copy(boxHeightPercent = value) } }
        ReadingStyleSlider(text(R.string.reading_style_opacity), if (settings.replaceText) 100 else settings.boxOpacity,
            10f..100f, 17, enabled = !settings.replaceText) { value -> onChange { it.copy(boxOpacity = value) } }
        Text(text(R.string.reading_style_geometry_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(false to R.string.reading_style_background, true to R.string.reading_style_foreground).forEach { (foreground, label) ->
            val colour = if (foreground) settings.boxTextArgb else settings.boxBackgroundArgb
            val colourKey = if (foreground) "foreground" else "background"
            Text(text(label), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                readingBoxColourChoices.forEach { colour ->
                    val selected = colour == if (foreground) settings.boxTextArgb else settings.boxBackgroundArgb
                    val description = text(R.string.reading_style_colour_choice, text(label), String.format(Locale.ROOT, "#%06X", colour and 0xffffff))
                    Surface(onClick = { onChange { if (foreground) it.copy(boxTextArgb = colour) else it.copy(boxBackgroundArgb = colour) } },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
                        shape = RoundedCornerShape(12.dp), color = Color(colour),
                        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (selected) Text("✓", color = if (android.graphics.Color.luminance(colour) > .5) Color.Black else Color.White)
                        }
                    }
                }
            }
            TextButton(onClick = { editingColour = if (editingColour == colourKey) null else colourKey },
                modifier = Modifier.testTag("reading-colour-$colourKey")) {
                Surface(Modifier.size(24.dp), color = Color(colour), shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {}
                Spacer(Modifier.width(8.dp))
                Text(text(R.string.reading_style_choose_colour, text(label), String.format(Locale.ROOT, "#%06X", colour and 0xffffff)))
            }
            if (editingColour == colourKey) key(colourKey) {
                HearthColourPicker(colour, Modifier.fillMaxWidth().testTag("reading-colour-picker-$colourKey"), onCommit = { chosen ->
                    onChange { if (foreground) it.copy(boxTextArgb = chosen) else it.copy(boxBackgroundArgb = chosen) }
                })
            }
        }
    }
}

@Composable private fun ReadingStyleSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean = true, change: (Int) -> Unit) {
    val text = rememberUiText()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text(R.string.reading_style_value, label, value), style = MaterialTheme.typography.titleSmall)
        Slider(value.toFloat(), { change(it.roundToInt()) }, valueRange = range, steps = steps, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
