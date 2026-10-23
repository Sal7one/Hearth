package com.sal7one.hearth.desktop.ui.screens.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sal7one.hearth.core.vision.OcrRect
import com.sal7one.hearth.desktop.DesktopReadingState
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.language.LanguageOption
import com.sal7one.hearth.desktop.ui.language.LanguagePicker
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

@Composable
internal fun ReadingScreen(
    state: DesktopReadingState = DesktopReadingState(),
    modelReady: Boolean = false, modelActivity: String? = null,
    target: String = "ar", targets: List<LanguageOption> = emptyList(),
    translate: Boolean = true, showOriginal: Boolean = false, translationIssue: String? = null,
    onOpen: () -> Unit = {}, onRead: () -> Unit = {}, onCancel: () -> Unit = {},
    onDownload: () -> Unit = {}, onSetup: () -> Unit = {}, onCrop: (OcrRect?) -> Unit = {},
    onTarget: (String) -> Unit = {}, onTranslate: (Boolean) -> Unit = {},
    onOriginal: (Boolean) -> Unit = {}, onCopy: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    var selecting by remember(state.image) { mutableStateOf(false) }
    PageScaffold(modifier, bottom = 40) {
        PageHeader("Image & manga", "Read the page, in your language",
            "Open a Japanese manga image. Hearth finds the text and bubbles, then uses your selected translator.",
            actions = {
                HearthButton("Open image", onOpen, tone = ButtonTone.Secondary, enabled = !state.busy)
                if (state.busy) HearthButton("Cancel", onCancel)
                else HearthButton(if(state.crop == null) "Read page" else "Read selected area", onRead,
                    enabled = state.image != null && modelReady && (!translate || translationIssue == null))
            })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Japanese →", color = HearthTheme.colors.textDim, style = HearthType.small)
                LanguagePicker("Output", target, targets, onTarget, enabled = !state.busy)
            }
            Toggle("Translate", translate, !state.busy, onTranslate)
            Toggle("Original image", showOriginal, true, onOriginal)
            HearthButton(if(selecting) "Draw on the page…" else "Draw an area", { selecting = !selecting },
                tone = ButtonTone.Secondary, size = ButtonSize.Small, enabled = state.image != null && !state.busy)
            if(state.crop != null) HearthButton("Whole page", { onCrop(null) }, tone = ButtonTone.Ghost,
                size = ButtonSize.Small, enabled = !state.busy)
        }
        Spacer(Modifier.height(22.dp))
        if(!modelReady) {
            Banner(modelActivity ?: "Get the manga bundle · 122 MiB. The detector highlights regions first; Manga OCR reads Japanese text locally.",
                action = { HearthButton(if(modelActivity == null) "Get models" else "Downloading…", onDownload,
                    tone = ButtonTone.Secondary, size = ButtonSize.Small, enabled = modelActivity == null) })
            Spacer(Modifier.height(18.dp))
        }
        if(translate && translationIssue != null) {
            Banner(translationIssue, tone = BannerTone.Warn, action = {
                HearthButton("Translation settings", onSetup, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            })
            Spacer(Modifier.height(18.dp))
        }
        state.error?.let { Banner(it, tone = BannerTone.Err); Spacer(Modifier.height(18.dp)) }
        Text(state.phase, color = HearthTheme.colors.muted, style = HearthType.small)
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints {
            if(maxWidth < 900.dp) Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PageImage(state, showOriginal, selecting, { selecting = false; onCrop(it) }, Modifier.fillMaxWidth().height(560.dp))
                Results(state, translate, onCopy, Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PageImage(state, showOriginal, selecting, { selecting = false; onCrop(it) }, Modifier.weight(1.3f).height(720.dp))
                Results(state, translate, onCopy, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(label, style = HearthType.small, color = HearthTheme.colors.textDim)
    }
}

@Composable
private fun Results(state: DesktopReadingState, translate: Boolean, onCopy: () -> Unit, modifier: Modifier) {
    SectionCard("Page text", modifier, detail = "${state.text.size} read · ${state.translations.size} translated",
        action = { HearthButton("Copy", onCopy, tone = ButtonTone.Ghost, size = ButtonSize.Small, enabled = state.text.isNotEmpty()) }) {
        if(state.text.isEmpty()) Text("Detected text will appear here. Draw an area for a small bubble or a crowded page.",
            style = HearthType.small, color = HearthTheme.colors.muted)
        state.text.forEachIndexed { index, item ->
            if(index > 0) { Spacer(Modifier.height(18.dp)); HorizontalDivider(color = HearthTheme.colors.border); Spacer(Modifier.height(18.dp)) }
            Text("${index + 1} · Japanese", style = HearthType.micro, color = HearthTheme.colors.muted)
            Spacer(Modifier.height(8.dp))
            Text(item.text, color = HearthTheme.colors.textDim, style = HearthType.body)
            if(translate) {
                Spacer(Modifier.height(10.dp))
                Text(state.translations[item.region.bounds] ?: if(state.busy) "Translating…" else "No translation",
                    color = HearthTheme.colors.text, style = HearthType.body.copy(textDirection = TextDirection.Content))
            }
        }
    }
}

@Composable
private fun PageImage(state: DesktopReadingState, original: Boolean, selecting: Boolean, onCrop: (OcrRect) -> Unit, modifier: Modifier) {
    val image = state.image
    val bitmap = remember(image) { image?.toComposeImageBitmap() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val scale = if(image == null) 1f else min(viewport.width.toFloat()/image.width, viewport.height.toFloat()/image.height).coerceAtLeast(.001f)
    val dx = (viewport.width - (image?.width ?: 0)*scale)/2f
    val dy = (viewport.height - (image?.height ?: 0)*scale)/2f
    fun rect(a: Offset, b: Offset): OcrRect? {
        image ?: return null
        val x = floor((minOf(a.x,b.x)-dx)/scale).toInt().coerceIn(0,image.width)
        val y = floor((minOf(a.y,b.y)-dy)/scale).toInt().coerceIn(0,image.height)
        val right = ceil((maxOf(a.x,b.x)-dx)/scale).toInt().coerceIn(x,image.width)
        val bottom = ceil((maxOf(a.y,b.y)-dy)/scale).toInt().coerceIn(y,image.height)
        return OcrRect(x,y,right-x,bottom-y).takeIf {it.width >= 8 && it.height >= 8}
    }
    Box(modifier.background(HearthTheme.colors.surface, RoundedCornerShape(16.dp)).onSizeChanged { viewport = it }) {
        if(bitmap == null) Text("Open a PNG or JPEG manga page", Modifier.align(Alignment.Center),
            color = HearthTheme.colors.muted, style = HearthType.small)
        Canvas(Modifier.fillMaxSize().pointerInput(image, viewport, selecting, state.busy) {
            if(selecting && !state.busy) detectDragGestures(
                onDragStart = { dragStart = it; dragEnd = it },
                onDragCancel = { dragStart = null; dragEnd = null },
                onDragEnd = {
                    val a = dragStart; val b = dragEnd
                    if(a != null && b != null) rect(a,b)?.let(onCrop)
                    dragStart = null; dragEnd = null
                },
                onDrag = { change, _ -> change.consume(); dragEnd = change.position },
            )
        }) {
            if(bitmap != null && image != null) drawImage(bitmap, dstOffset = IntOffset(dx.toInt(),dy.toInt()),
                dstSize = IntSize((image.width*scale).toInt(),(image.height*scale).toInt()))
            fun outline(r: OcrRect, color: Color, fill: Boolean) {
                val at = Offset(dx+r.x*scale,dy+r.y*scale); val size = Size(r.width*scale,r.height*scale)
                if(fill) drawRect(color.copy(alpha = .08f),at,size)
                drawRect(color,at,size,style = Stroke(1.5.dp.toPx()))
            }
            if(!original) state.regions.forEach { outline(it.bounds,Color(0xff18a99b),true) }
            state.crop?.let { outline(it,Color(0xffffbf55),false) }
            val a = dragStart; val b = dragEnd
            if(a != null && b != null) rect(a,b)?.let { outline(it,Color(0xffffbf55),true) }
        }
        if(!original) state.text.forEach { item ->
            val translated = state.translations[item.region.bounds] ?: return@forEach
            val r = item.region.bounds
            Box(Modifier.offset { IntOffset((dx+r.x*scale).toInt(),(dy+r.y*scale).toInt()) }
                .size(with(density) {(r.width*scale).toDp()},with(density) {(r.height*scale).toDp()})
                .background(Color.White,RoundedCornerShape(4.dp)).padding(3.dp), contentAlignment = Alignment.Center) {
                BasicText(translated, style = TextStyle(color = Color.Black,textAlign = TextAlign.Center,textDirection = TextDirection.Content),
                    autoSize = TextAutoSize.StepBased(8.sp,22.sp,1.sp), overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
