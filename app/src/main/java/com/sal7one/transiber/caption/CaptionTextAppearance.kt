package com.sal7one.transiber.caption

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.i18n.rememberUiText
import java.util.Locale
import kotlin.math.roundToInt
import com.sal7one.transiber.R as UiR

/** Text opacity is independent of the panel, including at a completely transparent background. */
internal fun captionTextColor(
    config: CaptionOverlayConfig,
    palette: OverlayPalette = paletteFor(config.theme),
    translated: Boolean = false,
    faded: Boolean = false,
): Color {
    val color = config.textColorArgb?.let { Color(it or 0xff000000.toInt()).copy(alpha = if (faded) .64f else 1f) }
        ?: if (faded) palette.onSurfaceFaded else if (translated) palette.accent else palette.onSurface
    return color.copy(alpha = color.alpha * config.textOpacity.coerceIn(10, 100) / 100f)
}

/** Opposite-tone shadow keeps both dark and light ink readable over moving video. */
internal fun captionTextShadow(color: Color, config: CaptionOverlayConfig, scale: Float = 1f, force: Boolean = false): Shadow? {
    if (!force && config.backgroundOpacity > 40) return null
    val contrast = if (color.luminance() > .3f) Color.Black else Color.White
    return Shadow(contrast.copy(alpha = color.alpha * .9f), Offset(0f, scale), 3f * scale)
}

/** Same inline picker in floating options and the full overlay editor; no extra overlay window or keyboard. */
@Composable
internal fun CaptionTextAppearanceControls(
    config: CaptionOverlayConfig,
    onChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    onPreview: (((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit)? = null,
) {
    val text = rememberUiText()
    var custom by rememberSaveable { mutableStateOf(false) }
    val swatches = listOf(
        UiR.string.caption_ink_soft_white to 0xffdedee3.toInt(),
        UiR.string.caption_ink_warm to 0xffffe7a2.toInt(),
        UiR.string.caption_ink_yellow to 0xffffd54f.toInt(),
        UiR.string.caption_ink_mint to 0xffa5e6c0.toInt(),
        UiR.string.caption_ink_sky to 0xff90caf9.toInt(),
        UiR.string.caption_ink_lavender to 0xffd1b3ff.toInt(),
        UiR.string.caption_ink_rose to 0xfff4acca.toInt(),
        UiR.string.caption_ink_dark to 0xff202124.toInt(),
    )
    val current = (config.textColorArgb ?: paletteFor(config.theme).onSurface.toArgb()) or 0xff000000.toInt()
    Column(Modifier.fillMaxWidth().testTag("caption-text-appearance"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text(UiR.string.caption_text_colour), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = config.textColorArgb == null,
                onClick = { onChange { it.copy(textColorArgb = null) } },
                modifier = Modifier.testTag("caption-ink-theme").captionFocusRing(), label = { Text(text(UiR.string.caption_ink_theme)) })
            swatches.forEach { (label, argb) ->
                val color = Color(argb)
                val selected = config.textColorArgb == argb
                Box(Modifier.size(48.dp)
                    .testTag("caption-ink-${argb.toUInt().toString(16)}")
                    .captionFocusRing(CircleShape)
                    .selectable(selected, role = Role.RadioButton, onClick = { onChange { it.copy(textColorArgb = argb) } })
                    .semantics { contentDescription = text(label) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).background(color, CircleShape)
                        .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Default.Check, null, Modifier.size(19.dp),
                            tint = if (color.luminance() > .3f) Color.Black else Color.White)
                    }
                }
            }
        }
        TextButton(onClick = { custom = !custom }, modifier = Modifier.testTag("caption-ink-custom").captionFocusRing()) {
            Text(text(UiR.string.caption_ink_custom, String.format(Locale.ROOT, "#%06X", current and 0xffffff)))
        }
        if (custom) {
            listOf(UiR.string.caption_ink_red to 16, UiR.string.caption_ink_green to 8, UiR.string.caption_ink_blue to 0).forEach { (label, shift) ->
                val value = (current ushr shift) and 255
                fun changed(value: Float, old: CaptionOverlayConfig): CaptionOverlayConfig {
                    val base = (old.textColorArgb ?: current) or 0xff000000.toInt()
                    val argb = (base and (255 shl shift).inv()) or (value.roundToInt().coerceIn(0, 255) shl shift)
                    return old.copy(textColorArgb = argb)
                }
                CaptionInkSlider(text(label), value.toFloat(), 0f..255f, "caption-ink-channel-$shift",
                    onPreview = onPreview?.let { preview -> { v -> preview { changed(v, it) } } },
                    onChange = { v -> onChange { changed(v, it) } })
            }
        }
        CaptionInkSlider(text(UiR.string.caption_text_opacity, config.textOpacity), config.textOpacity.toFloat(), 10f..100f,
            "caption-ink-opacity", onPreview = onPreview?.let { preview -> { value -> preview { it.copy(textOpacity = value.roundToInt()) } } },
            onChange = { value -> onChange { it.copy(textOpacity = value.roundToInt()) } })
        Row(Modifier.fillMaxWidth().testTag("caption-ink-preview")) {
            listOf(Color(0xff202124) to false, Color(0xffececf0) to true).forEach { (background, translated) ->
                val color = captionTextColor(config, translated = translated)
                Box(Modifier.weight(1f).heightIn(min = 64.dp).background(background).padding(10.dp), contentAlignment = Alignment.Center) {
                    Text(text(UiR.string.caption_ink_preview), color = color,
                        style = TextStyle(shadow = captionTextShadow(color, config, force = true)))
                }
            }
        }
        Text(text(UiR.string.caption_text_colour_help), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CaptionInkSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String,
    onPreview: ((Float) -> Unit)?, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(draft, onValueChange = { draft = it; onPreview?.invoke(it) }, valueRange = range,
            onValueChangeFinished = { onChange(draft) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).captionFocusRing().semantics { contentDescription = label })
    }
}

/** Clear keyboard/D-pad focus, including on the editor shown on Android TV. */
@Composable
private fun Modifier.captionFocusRing(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
}
