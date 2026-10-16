package com.sal7one.transiber.caption

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A current subtitle replaces the previous utterance; panel mode retains selected history. */
internal fun tvCaptionText(content: CaptionReadingSnapshot, config: CaptionOverlayConfig): String {
    fun pair(original: String, translated: String?): String {
        val translation = translated?.takeIf { it.isNotBlank() }
        return buildList {
            if (original.isNotBlank() && (config.display != CaptionDisplay.TRANSLATED || translation == null)) add(original)
            if (config.display != CaptionDisplay.ORIGINAL && translation != null) add(translation)
        }.joinToString("\n")
    }
    if (config.tvSubtitleMode) {
        return if (content.partial.isNotBlank()) pair(content.partial, content.partialTranslation)
        else content.history.lastOrNull()?.let { pair(it.original, it.translation) }.orEmpty()
    }
    return buildList {
        CaptionReading.visibleFinals(content, config.historyLines, reading = false).forEach { add(pair(it.original, it.translation)) }
        if (content.partial.isNotBlank()) add(pair(content.partial, content.partialTranslation))
    }.filter { it.isNotBlank() }.joinToString("\n")
}

/** Shared by the live TV window and editor preview: no controls, indicators, borders or handles. */
@Composable
internal fun TvOverlayText(text: String, config: CaptionOverlayConfig, modifier: Modifier = Modifier, scale: Float = 1f) {
    val palette = paletteFor(config.theme)
    val scroll = rememberScrollState()
    LaunchedEffect(text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val subtitles = config.tvSubtitleMode
    Box(modifier.fillMaxSize().clipToBounds().then(if (subtitles) Modifier else Modifier.background(
        palette.surface.copy(alpha = effectiveSurfaceAlpha(palette.surface.alpha, config.backgroundOpacity))))) {
        Text(text, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().verticalScroll(scroll)
            .padding(horizontal = (6 * scale).dp, vertical = (2 * scale).dp),
            color = if (subtitles) Color.White else palette.onSurface,
            fontSize = ((if (subtitles) 22 else 18) * config.fontScale.multiplier * scale).sp,
            lineHeight = ((if (subtitles) 28 else 24) * config.fontScale.multiplier * scale).sp,
            textAlign = if (subtitles) TextAlign.Center else TextAlign.Start,
            style = TextStyle(textDirection = TextDirection.Content, shadow = if (subtitles) Shadow(Color.Black, Offset(scale, scale), 4f * scale) else null))
    }
}
