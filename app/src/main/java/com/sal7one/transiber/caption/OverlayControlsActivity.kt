package com.sal7one.transiber.caption

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.shortcuts.OverlayLaunchActivity
import com.sal7one.transiber.shortcuts.OverlaySetupVisibility
import com.sal7one.transiber.ui.theme.HearthTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A normal, remote-focusable window; never requires interaction with the caption bubble. */
class OverlayControlsActivity : AppCompatActivity() {
    private var visibilityLease: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES) {
            val tile = androidx.core.content.IntentCompat.getParcelableExtra(intent,
                Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName::class.java)
            if (tile?.className == com.sal7one.transiber.shortcuts.ReadingTileService::class.java.name) {
                startActivity(Intent(this, MainActivity::class.java).putExtra("page", 10))
                finish()
                return
            }
        }
        setContent {
            HearthTheme {
                OverlayControls(
                    onReturn = { finish() },
                    onStart = {
                        startActivity(Intent(this, OverlayLaunchActivity::class.java))
                        finish()
                    },
                    onSpeechSetup = {
                        startActivity(Intent(this, MainActivity::class.java).putExtra("page", 0)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    },
                    onCloseApp = { stopAndCloseHearth() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visibilityLease = OverlaySetupVisibility.acquire()
    }

    override fun onStop() {
        visibilityLease?.close(); visibilityLease = null
        super.onStop()
    }

    override fun onDestroy() {
        visibilityLease?.close(); visibilityLease = null
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, OverlayControlsActivity::class.java))
        }
    }
}

private enum class OverlayEditorCategory(val title: Int) {
    POSITION(R.string.overlay_category_position), SIZE(R.string.overlay_category_size),
    TEXT(R.string.overlay_category_text), STYLE(R.string.overlay_category_style),
    SESSION(R.string.overlay_category_session),
}

@Composable
private fun OverlayControls(onReturn: () -> Unit, onStart: () -> Unit, onSpeechSetup: () -> Unit, onCloseApp: () -> Unit) {
    val text = rememberUiText()
    val context = androidx.compose.ui.platform.LocalContext.current
    val television = remember(context) { TvControls.isTvDevice(context) }
    val scope = rememberCoroutineScope()
    val config by remember(context) { CaptionConfigStore.config(context) }
        .collectAsStateWithLifecycle(initialValue = null)
    val session by CaptionCaptureService.session.collectAsStateWithLifecycle()
    val categoryFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    var category by rememberSaveable { mutableStateOf(OverlayEditorCategory.POSITION) }
    LaunchedEffect(Unit) { categoryFocus.requestFocus() }
    var pendingWrite by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(transform: (CaptionOverlayConfig) -> CaptionOverlayConfig) {
        val previous = pendingWrite
        pendingWrite = scope.launch {
            previous?.join()
            try { CaptionConfigStore.update(context, transform); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }
    fun afterSaving(action: () -> Unit) { scope.launch { pendingWrite?.join(); action() } }
    BackHandler { afterSaving(onReturn) }
    val density = LocalDensity.current.density
    val screen = LocalConfiguration.current
    val margin = (8 * density).toInt()
    val viewport = OverlayViewport(margin, margin, (screen.screenWidthDp * density).toInt() - margin,
        (screen.screenHeightDp * density).toInt() - margin)
    val step = (24 * density).toInt()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val status = text(when {
        !session.running -> R.string.overlay_status_stopped
        session.error != null -> R.string.service_live_captions_error_9f465
        session.paused -> R.string.overlay_status_paused
        else -> R.string.overlay_status_listening
    })

    @Composable fun Options(modifier: Modifier) {
        Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            // A new category starts at the top; only this pane scrolls, never the preview.
            key(category) {
                val scroll = rememberScrollState()
                Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().focusRequester(optionsFocus).focusGroup()
                    .verticalScroll(scroll).padding(16.dp).testTag("overlay_editor_options"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text(category.title), style = MaterialTheme.typography.titleLarge)
                    val cfg = config
                    if (cfg == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else when (category) {
                        OverlayEditorCategory.POSITION -> {
                            CaptionAnchor.entries.forEach { anchor ->
                                RemoteButton(text.label(anchor), modifier = Modifier.fillMaxWidth(), selected = cfg.anchor == anchor,
                                    onClick = { update { it.copy(anchor = anchor, xOffsetPx = 0, yOffsetPx = 0) } })
                            }
                            HorizontalDivider()
                            listOf(
                                Triple(R.string.overlay_move_left, -step, 0), Triple(R.string.overlay_move_right, step, 0),
                                Triple(R.string.overlay_move_up, 0, -step), Triple(R.string.overlay_move_down, 0, step),
                            ).forEach { (label, dx, dy) ->
                                RemoteButton(text(label), modifier = Modifier.fillMaxWidth(),
                                    onClick = { update { it.nudgeOverlay(viewport, density, dx, dy, television) } })
                            }
                        }
                        OverlayEditorCategory.SIZE -> {
                            RemoteAdjustment(text(R.string.overlay_width, cfg.widthPercent), cfg.widthPercent > 40, cfg.widthPercent < 100,
                                { update { it.copy(widthPercent = it.widthPercent - 5) } }, { update { it.copy(widthPercent = it.widthPercent + 5) } })
                            val height = (overlayHeightPx(viewport.height, density, cfg, television) / density).toInt()
                            RemoteAdjustment(if (television) text(R.string.tv_overlay_height, height) else text(R.string.overlay_height, height * 100 / (viewport.height / density).toInt().coerceAtLeast(1)),
                                height > minimumOverlayHeightDp(television), height < minOf(600, (viewport.height / density).toInt()),
                                { update { it.copy(bubbleHeightDp = height - if (television) 8 else 24) } }, { update { it.copy(bubbleHeightDp = height + if (television) 8 else 24) } })
                        }
                        OverlayEditorCategory.TEXT -> {
                            if (cfg.mode == CaptionMode.TRANSLATE) {
                                Text(text(R.string.ui_show_d97d1), style = MaterialTheme.typography.titleMedium)
                                CaptionDisplay.entries.forEach { display ->
                                    RemoteButton(text.label(display), modifier = Modifier.fillMaxWidth(), selected = cfg.display == display,
                                        onClick = { update { it.copy(display = display) } })
                                }
                                if (cfg.display == CaptionDisplay.TRANSLATED) {
                                    RemoteToggle(text(R.string.caption_show_original_while_translating), cfg.showOriginalWhileTranslating) { checked ->
                                        update { it.copy(showOriginalWhileTranslating = checked) }
                                    }
                                    Text(text(R.string.caption_show_original_while_translating_help), style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                            }
                            CaptionFontScale.entries.forEach { size ->
                                RemoteButton(text.label(size), modifier = Modifier.fillMaxWidth(), selected = cfg.fontScale == size,
                                    onClick = { update { it.copy(fontScale = size) } })
                            }
                            RemoteAdjustment(text(R.string.overlay_previous_lines, cfg.historyLines), cfg.historyLines > 0, cfg.historyLines < 8,
                                { update { it.copy(historyLines = it.historyLines - 1) } }, { update { it.copy(historyLines = it.historyLines + 1) } })
                        }
                        OverlayEditorCategory.STYLE -> {
                            if (television) {
                                RemoteButton(text(R.string.tv_overlay_subtitles), modifier = Modifier.fillMaxWidth(), selected = cfg.tvSubtitleMode,
                                    onClick = { update { it.copy(tvSubtitleMode = true, bubbleHeightDp = TV_OVERLAY_DEFAULT_HEIGHT_DP) } })
                                RemoteButton(text(R.string.tv_overlay_panel), modifier = Modifier.fillMaxWidth(), selected = !cfg.tvSubtitleMode,
                                    onClick = { update { it.copy(tvSubtitleMode = false) } })
                                Text(text(R.string.tv_overlay_subtitles_help), style = MaterialTheme.typography.bodySmall)
                            }
                            CaptionTheme.entries.forEach { theme ->
                                RemoteButton(text.label(theme), modifier = Modifier.fillMaxWidth(), selected = cfg.theme == theme,
                                    onClick = { update { it.copy(theme = theme) } })
                            }
                            RemoteAdjustment(text(R.string.overlay_opacity, cfg.backgroundOpacity), cfg.backgroundOpacity > 0, cfg.backgroundOpacity < 100,
                                { update { it.copy(backgroundOpacity = it.backgroundOpacity - 10) } }, { update { it.copy(backgroundOpacity = it.backgroundOpacity + 10) } })
                            RemoteButton(text(R.string.overlay_reset), modifier = Modifier.fillMaxWidth(),
                                onClick = { update { it.resetOverlayPresentation(OverlayOrientation.from(screen.orientation), television) } })
                        }
                        OverlayEditorCategory.SESSION -> {
                            RemoteButton(text(if (session.running) {
                                if (session.overlayVisible) R.string.service_hide_bubble_658d0 else R.string.service_show_bubble_9a42e
                            } else R.string.ui_start_captions_68e1c), modifier = Modifier.fillMaxWidth(), onClick = {
                                if (session.running) CaptionCaptureService.toggleVisibility(context) else afterSaving(onStart)
                            })
                            if (session.running) {
                                RemoteButton(text(if (session.paused) R.string.service_resume_b3bd0 else R.string.service_pause_78196),
                                    modifier = Modifier.fillMaxWidth(), enabled = session.error == null,
                                    onClick = { CaptionCaptureService.togglePause(context) })
                                RemoteButton(text(R.string.ui_stop_captions_58b64), modifier = Modifier.fillMaxWidth(),
                                    onClick = { CaptionCaptureService.stop(context) })
                                RemoteButton(text(R.string.service_silence_voice_7c1e9), modifier = Modifier.fillMaxWidth(),
                                    onClick = { CaptionCaptureService.silence(context) })
                            }
                            RemoteButton(text(R.string.overlay_speech_setup), modifier = Modifier.fillMaxWidth(), onClick = { afterSaving(onSpeechSetup) })
                            session.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                            Text(text(R.string.overlay_controls_visibility_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    // Focusable footer lets the remote reach the bottom of long help/error text.
                    RemoteButton(text(R.string.overlay_return), modifier = Modifier.fillMaxWidth(), onClick = { afterSaving(onReturn) })
                }
                val thumbColor = MaterialTheme.colorScheme.primary
                val trackColor = MaterialTheme.colorScheme.outlineVariant
                if (scroll.maxValue > 0) Canvas(Modifier.align(Alignment.CenterEnd)
                    .padding(vertical = 12.dp, horizontal = 4.dp).width(3.dp).fillMaxHeight()) {
                    val thumbHeight = size.height * (size.height / (size.height + scroll.maxValue)).coerceIn(.1f, 1f)
                    val offset = scroll.value.toFloat() / scroll.maxValue * (size.height - thumbHeight)
                    drawRoundRect(trackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width))
                    drawRoundRect(thumbColor, topLeft = androidx.compose.ui.geometry.Offset(0f, offset),
                        size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width))
                }
                }
            }
        }
    }
    @Composable fun Preview(modifier: Modifier) {
        Column(modifier.testTag("overlay_editor_preview_pane"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text(R.string.overlay_preview), style = MaterialTheme.typography.titleLarge)
            val cfg = config
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (cfg != null) OverlayPositionPreview(cfg, viewport, density)
            }
            if (screen.screenWidthDp >= 720) Text(text(R.string.overlay_preview_help), style = MaterialTheme.typography.bodySmall)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text(R.string.overlay_controls_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = if (session.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (screen.screenWidthDp >= 720) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(136.dp).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayEditorCategory.entries.forEach { item ->
                            RemoteButton(text(item.title), modifier = Modifier.fillMaxWidth().focusProperties {
                                if (rtl) left = optionsFocus else right = optionsFocus
                            }, selected = category == item, focus = if (item == category) categoryFocus else null,
                                onClick = { category = item })
                        }
                    }
                    Options(Modifier.width(248.dp).fillMaxHeight())
                    Preview(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // Compact displays retain every action with a pinned preview above the scrolling options.
                Preview(Modifier.fillMaxWidth().height((screen.screenHeightDp * .25f).coerceIn(100f, 200f).dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(OverlayEditorCategory.entries.size) { index ->
                        val item = OverlayEditorCategory.entries[index]
                        RemoteButton(text(item.title), selected = category == item,
                            focus = if (item == category) categoryFocus else null, onClick = { category = item })
                    }
                }
                Options(Modifier.fillMaxWidth().weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteButton(text(R.string.overlay_return), modifier = if (screen.screenWidthDp < 720) Modifier.weight(1f) else Modifier,
                    onClick = { afterSaving(onReturn) })
                RemoteButton(text(R.string.overlay_stop_close), modifier = if (screen.screenWidthDp < 720) Modifier.weight(1f) else Modifier,
                    onClick = { afterSaving(onCloseApp) })
                if (screen.screenWidthDp >= 720) Text(text(R.string.overlay_editor_help), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RemoteAdjustment(label: String, canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val text = rememberUiText()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton(text(R.string.overlay_decrease), modifier = Modifier.weight(1f), enabled = canDecrease,
                description = "$label · ${text(R.string.overlay_decrease)}", onClick = onDecrease)
            RemoteButton(text(R.string.overlay_increase), modifier = Modifier.weight(1f), enabled = canIncrease,
                description = "$label · ${text(R.string.overlay_increase)}", onClick = onIncrease)
        }
    }
}

/** The whole switch row has one remote focus target and an explicit checked state. */
@Composable
private fun RemoteToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape)
            .border(if (focused) 3.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Material's subtle default focus is hard to see from a couch. */
@Composable
internal fun RemoteButton(label: String, enabled: Boolean = true, selected: Boolean = false, description: String? = null, focus: FocusRequester? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier).onFocusChanged { focused = it.isFocused }
            .semantics { description?.let { contentDescription = it }; this.selected = selected },
        border = BorderStroke(if (focused) 3.dp else 1.dp,
            if (focused || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor =
            if (focused || selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) { Text(label) }
}

/** Geometry uses physical left/top even in RTL, matching the real WindowManager overlay. */
@Composable
private fun OverlayPositionPreview(config: CaptionOverlayConfig, viewport: OverlayViewport, density: Float) {
    val text = rememberUiText()
    val context = LocalContext.current
    val television = remember(context) { TvControls.isTvDevice(context) }
    val placement = placeOverlay(viewport, viewport.width * config.widthPercent / 100,
        overlayHeightPx(viewport.height, density, config, television), config.anchor, config.xOffsetPx, config.yOffsetPx)
    val palette = paletteFor(config.theme)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ratio = viewport.width.toFloat() / viewport.height
        val fittedWidth = minOf(maxWidth, maxHeight * ratio)
        BoxWithConstraints(Modifier.align(Alignment.Center).width(fittedWidth).aspectRatio(ratio)
        .clipToBounds().testTag("overlay_editor_preview")
        .background(Color(0xFF17202F))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))) {
        val scale = maxWidth / viewport.width
        val textScale = maxWidth.value / (viewport.width / density)
        Image(painterResource(R.drawable.tv_preview_scene), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val captionModifier = Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset(x = scale * (placement.x - viewport.left), y = scale * (placement.y - viewport.top))
            .size(scale * placement.width, scale * placement.height).clipToBounds()
        if (television) {
            TvOverlayText(text(R.string.overlay_preview_sample), config, captionModifier, textScale)
        } else Box(captionModifier
            .background(palette.surface.copy(alpha = effectiveSurfaceAlpha(palette.surface.alpha, config.backgroundOpacity)), RoundedCornerShape(4.dp))
            .border(1.dp, palette.accent.copy(alpha = .6f), RoundedCornerShape(4.dp))) {
            Column(Modifier.padding((8 * textScale).dp), verticalArrangement = Arrangement.spacedBy((4 * textScale).dp)) {
                Box(Modifier.width((28 * textScale).dp).height(2.dp).background(palette.onSurfaceMuted))
                if (config.historyLines > 0) Text(text(R.string.overlay_preview_previous),
                    color = palette.onSurfaceFaded, fontSize = (14 * config.fontScale.multiplier * textScale).sp,
                    lineHeight = (20 * config.fontScale.multiplier * textScale).sp)
                Text(text(R.string.overlay_preview_sample), color = palette.onSurface,
                    fontSize = (14 * config.fontScale.multiplier * textScale).sp,
                    lineHeight = (20 * config.fontScale.multiplier * textScale).sp)
            }
        }
    }
}
}
