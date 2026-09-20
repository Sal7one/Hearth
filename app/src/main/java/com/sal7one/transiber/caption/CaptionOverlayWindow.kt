package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.LocalTextStyle
import com.sal7one.transiber.byok.ApiKeyStore
import com.sal7one.transiber.byok.ByokPolicy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.caption.CaptionEngineController.State
import com.sal7one.transiber.caption.CaptionEngineController.Status
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private data class OverlayPalette(
    val surface: Color,
    val onSurface: Color,
    val onSurfaceFaded: Color,
    // Brighter than onSurfaceFaded: used for interactive control labels (chips,
    // sliders, toggles) so unselected options stay legible on all four themes.
    val onSurfaceMuted: Color,
    val accent: Color,
    val chip: Color,
)

/** Surface alpha = theme surface alpha scaled by the user's opacity %, clamped.
 *  (Scales instead of replacing so SUBTLE stays subtle and HIGH_CONTRAST solid.) */
internal fun effectiveSurfaceAlpha(baseAlpha: Float, opacityPercent: Int): Float =
    (baseAlpha * opacityPercent / 100f).coerceIn(0f, 1f)

private fun paletteFor(theme: CaptionTheme): OverlayPalette = when (theme) {
    CaptionTheme.DARK -> OverlayPalette(
        surface = Color(0xF01C1B1F),
        onSurface = Color(0xFFF4EFF4),
        onSurfaceFaded = Color(0xFFBDB8BD),
        onSurfaceMuted = Color(0xCCF4EFF4),
        accent = Color(0xFFD7BCFF),
        chip = Color(0x33D7BCFF),
    )
    CaptionTheme.HIGH_CONTRAST -> OverlayPalette(
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceFaded = Color(0xFFCCCCCC),
        onSurfaceMuted = Color(0xFFFFFFFF),
        accent = Color(0xFFFFFF00),
        chip = Color(0x33FFFFFF),
    )
    CaptionTheme.LIGHT -> OverlayPalette(
        surface = Color(0xF6FFFFFF),
        onSurface = Color(0xFF1C1B1F),
        onSurfaceFaded = Color(0x991C1B1F),
        onSurfaceMuted = Color(0xC71C1B1F),
        accent = Color(0xFF6750A4),
        chip = Color(0x1F6750A4),
    )
    CaptionTheme.SUBTLE -> OverlayPalette(
        surface = Color(0x80000000),
        onSurface = Color(0xFFEFEEF0),
        onSurfaceFaded = Color(0x80EFEEF0),
        onSurfaceMuted = Color(0xB3EFEEF0),
        accent = Color(0xFFC6C4FF),
        chip = Color(0x24C6C4FF),
    )
}

/**
 * The caption overlay: a draggable caption bubble with a compact control
 * strip and separate appearance / CC settings. Rendered inside a WindowManager overlay window.
 */
@Composable
fun CaptionOverlayWindow(
    config: StateFlow<CaptionOverlayConfig>,
    engineState: StateFlow<State>,
    onConfigChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragFinished: () -> Unit = {},
    onClose: () -> Unit,
    onClear: () -> Unit,
    availableHeightDp: Float = 240f,
) {
    val uiText = rememberUiText()

    val cfg by config.collectAsStateWithLifecycle()
    val state by engineState.collectAsStateWithLifecycle()
    val palette = paletteFor(cfg.theme)
    val scrollState = rememberScrollState()
    // Keep settings navigation alive while the panel is replaced by live captions.
    val appearanceScrollState = rememberScrollState()
    val translationScrollState = rememberScrollState()
    var appearanceSettings by remember { mutableStateOf(true) }
    var showReadAloud by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<CaptionReadingSnapshot?>(null) }
    val clipboard = LocalClipboardManager.current
    val content = held ?: CaptionReading.snapshot(state, cfg.showPartial)
    val bodyScroll = if (held == null) scrollState else rememberScrollState(initial = Int.MAX_VALUE)

    LaunchedEffect(cfg.tapThrough) { if (cfg.tapThrough) held = null }
    val height = availableHeightDp.coerceAtLeast(1f)
    Column(
        modifier = Modifier.fillMaxWidth().height(height.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface.copy(alpha = if (cfg.showSettings) 0.97f else effectiveSurfaceAlpha(palette.surface.alpha, cfg.backgroundOpacity)))
            .border(0.5.dp, palette.onSurfaceFaded, RoundedCornerShape(16.dp)),
    ) {
        if (cfg.tapThrough) Text(uiText(UiR.string.ui_tap_lock_to_restore_controls_drag_lock_to_move_20d73),
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = palette.onSurface, fontSize = 12.sp)
        else ControlStrip(cfg, state, palette, true, onDrag, onDragFinished, onClose, { held = null; onClear() }, onConfigChange)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (cfg.showSettings && cfg.languagePicker != null) {
                val which = cfg.languagePicker
                val cloudMode = com.sal7one.transiber.byok.CloudConfigStore.sttMode(LocalContext.current)
                MaterialTheme(colorScheme = if (cfg.theme == CaptionTheme.LIGHT) androidx.compose.material3.lightColorScheme()
                    else androidx.compose.material3.darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        LanguagePickerContent(
                            title = if (which == CaptionLanguagePicker.SOURCE) uiText(UiR.string.ui_spoken_language_cc_c652a) else uiText(UiR.string.ui_translate_to_a1ba6),
                            choices = if (which == CaptionLanguagePicker.SOURCE) CaptionLanguages.source(cfg, cloudMode, rememberCaptionLanguageModel(cfg)) else com.sal7one.transiber.translation.captionTranslationChoices(cfg, cloudMode),
                            selected = if (which == CaptionLanguagePicker.SOURCE) {
                                if (cfg.engine == CaptionEngineChoice.VOSK) "model" else CaptionLanguages.effectiveSource(cfg, cloudMode, rememberCaptionLanguageModel(cfg))
                            } else cfg.target.languageTag,
                            onSelect = { code -> onConfigChange {
                                if (which == CaptionLanguagePicker.SOURCE) it.copy(streamLanguage = if (code == "model") "auto" else code, languagePicker = null)
                                else it.copy(target = TranslationTarget.of(code), languagePicker = null)
                            } },
                            onDismiss = { onConfigChange { it.copy(languagePicker = null) } },
                            searchable = false,
                        )
                    }
                }
            } else if (cfg.showSettings) {
                SettingsPanel(
                    cfg = cfg, palette = palette, currentHeightDp = height,
                    appearance = appearanceSettings,
                    onAppearanceChange = { appearanceSettings = it },
                    scrollState = if (appearanceSettings) appearanceScrollState else translationScrollState,
                    showReadAloud = showReadAloud,
                    onShowReadAloudChange = { showReadAloud = it },
                    onConfigChange = onConfigChange,
                    onClear = { held = null; onClear() },
                )
            } else {
                Column {
                    if (!cfg.tapThrough) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            held = if (held == null) CaptionReading.snapshot(state, cfg.showPartial) else null
                        }) {
                            Text(if (held == null) uiText(UiR.string.ui_read_history_2f89b) else uiText(UiR.string.ui_back_to_live_e83c9), color = palette.accent)
                        }
                        if (held != null) {
                            TextButton(onClick = {
                                val text = buildString {
                                    content.history.forEach { line ->
                                        if (cfg.display != CaptionDisplay.TRANSLATED || line.translation == null) appendLine(line.original)
                                        if (cfg.display != CaptionDisplay.ORIGINAL) line.translation?.let { appendLine(it) }
                                    }
                                    if (content.partial.isNotBlank()) {
                                        if (cfg.display != CaptionDisplay.TRANSLATED || content.partialTranslation == null) appendLine(content.partial)
                                        if (cfg.display != CaptionDisplay.ORIGINAL) content.partialTranslation?.let { appendLine(it) }
                                    }
                                }.trim()
                                clipboard.setText(AnnotatedString(text))
                            }) { Text(uiText(UiR.string.ui_copy_text_06a76), color = palette.accent) }
                        }
                    }
                    if (held != null) Text(
                        uiText(UiR.string.ui_text_held_capture_continues_latest_120_segments_retained_ff712),
                        modifier = Modifier.padding(horizontal = 12.dp), color = palette.onSurfaceMuted, fontSize = 11.sp,
                    )
                    Box(Modifier.weight(1f)) {
                        SelectionContainer { CaptionBody(cfg, state, content, held != null, palette, bodyScroll) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlStrip(
    cfg: CaptionOverlayConfig,
    state: State,
    palette: OverlayPalette,
    dragEnabled: Boolean,
    onDrag: (Int, Int) -> Unit,
    onDragFinished: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onConfigChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
) {
    val uiText = rememberUiText()

    val moveStep = with(LocalDensity.current) { 32.dp.roundToPx() }
    val moveActions = listOf(
        CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_up_d94cb)) { onDrag(0, -moveStep); onDragFinished(); true },
        CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_down_f1f06)) { onDrag(0, moveStep); onDragFinished(); true },
        CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_left_920be)) { onDrag(-moveStep, 0); onDragFinished(); true },
        CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_right_a939e)) { onDrag(moveStep, 0); onDragFinished(); true },
    )
    val stripDrag = if (dragEnabled) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { onDragFinished() },
            ) { change, dragAmount ->
                change.consume()
                onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
            }
        }
    } else {
        Modifier
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val controlSize = 48.dp
    val narrow = maxWidth < 320.dp
    Column {
    if (narrow) StatusBadge(state, cfg, palette, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Only this handle drags the window; scrolling and buttons own their gestures.
        Icon(
            Icons.Default.DragIndicator,
            contentDescription = uiText(UiR.string.ui_drag_to_move_captions_51a37),
            tint = palette.onSurfaceFaded,
            modifier = Modifier.size(48.dp).then(stripDrag).semantics { customActions = moveActions },
        )

        if (!narrow) StatusBadge(state = state, cfg = cfg, palette = palette, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))

        IconButton(onClick = { onConfigChange { it.copy(paused = !it.paused) } }, enabled = state.status != Status.ERROR, modifier = Modifier.size(controlSize)) {
            Icon(
                if (cfg.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                if (cfg.paused) uiText(UiR.string.ui_resume_captions_b31f8) else uiText(UiR.string.ui_pause_captions_dc66c),
                tint = palette.onSurfaceFaded,
            )
        }
        IconButton(onClick = onClear, modifier = Modifier.size(controlSize)) {
            Icon(Icons.Default.Delete, uiText(UiR.string.ui_clear_previous_text_and_pending_translations_4cbd5), tint = palette.onSurfaceFaded)
        }
        IconButton(onClick = { onConfigChange { it.copy(showSettings = !it.showSettings, languagePicker = null) } }, modifier = Modifier.size(controlSize)) {
            Icon(Icons.Default.Settings, uiText(UiR.string.ui_caption_settings_da0c8), tint = palette.onSurfaceFaded)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(controlSize)) {
            Icon(Icons.Default.Close, uiText(UiR.string.ui_stop_captions_58b64), tint = palette.onSurfaceFaded)
        }
    }
    }
    }
}

@Composable
private fun StatusBadge(state: State, cfg: CaptionOverlayConfig, palette: OverlayPalette, modifier: Modifier) {
    val uiText = rememberUiText()

    val label = when {
        cfg.paused -> uiText(UiR.string.ui_paused_c7dfb)
        state.status == Status.LOADING_MODEL -> uiText(UiR.string.ui_loading_1_s_aba14, state.modelName ?: "model")
        state.status == Status.ERROR -> state.error ?: uiText(UiR.string.ui_error_7f2f6)
        state.status == Status.CAPTURE_SILENT && cfg.source == CaptionSource.PLAYBACK_CAPTURE ->
            uiText(UiR.string.ui_no_capturable_audio_app_may_block_capture_try_microphone_input_709c8)
        state.status == Status.RUNNING && cfg.paused -> uiText(UiR.string.ui_paused_c7dfb)
        state.status == Status.RUNNING && cfg.mode == CaptionMode.TRANSLATE ->
            uiText(UiR.string.ui_translating_1_s_0d960, state.engineLabel)
        state.status == Status.RUNNING -> uiText(UiR.string.ui_captions_1_s_31caa, state.engineLabel)
        else -> state.status.name.lowercase(java.util.Locale.ROOT)
    }
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(palette.chip)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = palette.accent,
        fontSize = 10.sp,
        maxLines = 1,
    )
}

@Composable
private fun CaptionBody(
    cfg: CaptionOverlayConfig,
    state: State,
    content: CaptionReadingSnapshot,
    reading: Boolean,
    palette: OverlayPalette,
    scrollState: ScrollState,
) {
    val uiText = rememberUiText()

    // A final remains the current caption even when previous-history is disabled.
    // One bounded scrolling viewport; no content-height feedback or whole-card drag.
    val lines = CaptionReading.visibleFinals(content, cfg.historyLines, reading)
    LaunchedEffect(content, reading, scrollState.maxValue) {
        if (!reading && !scrollState.isScrollInProgress) scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(scrollState).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (cfg.paused) {
            Text(uiText(UiR.string.ui_paused_resume_from_the_bubble_or_notification_58a85), color = palette.accent, fontSize = 13.sp)
        }
        if (content.history.isEmpty() && content.partial.isBlank()) {
            Text(when (state.status) {
                Status.LOADING_MODEL -> uiText(UiR.string.ui_connecting_loading_44380)
                Status.ERROR -> state.error ?: uiText(UiR.string.ui_caption_engine_failed_fa936)
                Status.CAPTURE_SILENT -> uiText(UiR.string.ui_no_capturable_audio_play_a_stream_if_it_stays_silent_the_app_may_fdefa)
                else -> uiText(UiR.string.ui_listening_6c19a)
            }, color = palette.onSurface, fontSize = 14.sp)
        }
        // Keep diagnostic counters above the text: live scrolling must land on captions.
        state.localTranslationMetrics?.let {
            Text(it, color = palette.onSurfaceMuted, fontSize = 11.sp)
        }
        state.localSpeechMetrics?.let {
            Text(it, color = palette.onSurfaceMuted, fontSize = 11.sp)
        }
        lines.forEachIndexed { index, line ->
            LinePair(line.original, line.translation, cfg, palette,
                faded = content.partial.isNotBlank() || index < lines.lastIndex)
        }
        if (content.partial.isNotBlank()) {
            LinePair(content.partial, content.partialTranslation, cfg, palette, false)
        }
        state.error?.takeIf { content.history.isNotEmpty() || content.partial.isNotBlank() }?.let {
            Text(it, color = palette.accent, fontSize = 12.sp)
        }
        state.translationNotice?.let {
            Text(it, color = palette.accent, fontSize = 12.sp)
        }
    }
}

/**
 * One utterance rendered per the display setting: original only, translation
 * only, or both. Arabic (RTL) translation text is right-aligned and uses
 * Compose's bidi-aware text resolution.
 */
@Composable
private fun LinePair(
    original: String,
    translation: String?,
    cfg: CaptionOverlayConfig,
    palette: OverlayPalette,
    faded: Boolean,
) {
    val showOriginal = original.isNotBlank() && (cfg.display != CaptionDisplay.TRANSLATED || translation == null)
    val showTranslation = cfg.display != CaptionDisplay.ORIGINAL && translation != null

    if (showOriginal) {
        Text(
            text = original,
            color = if (faded) palette.onSurfaceFaded else palette.onSurface,
            fontSize = (14.sp * cfg.fontScale.multiplier),
            lineHeight = (20.sp * cfg.fontScale.multiplier),
            textAlign = if (cfg.translationRtl) TextAlign.Start else null,
        )
    }
    if (showTranslation && translation != null) {
        Text(
            text = translation,
            color = if (faded) palette.onSurfaceFaded else palette.accent,
            fontSize = (15.sp * cfg.fontScale.multiplier),
            lineHeight = (21.sp * cfg.fontScale.multiplier),
            // RTL targets render right-aligned; Compose handles bidi runs in
            // mixed-script lines automatically.
            textAlign = if (cfg.translationRtl) TextAlign.Right else TextAlign.Start,
        )
    }
}

@Composable
private fun SettingsPanel(
    cfg: CaptionOverlayConfig,
    palette: OverlayPalette,
    currentHeightDp: Float,
    appearance: Boolean,
    onAppearanceChange: (Boolean) -> Unit,
    scrollState: ScrollState,
    showReadAloud: Boolean,
    onShowReadAloudChange: (Boolean) -> Unit,
    onConfigChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    onClear: () -> Unit,
) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    fun openSetup(page: Int) {
        context.startActivity(android.content.Intent(context, com.sal7one.transiber.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("page", page))
        onConfigChange { it.copy(showSettings = false) }
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = palette.accent, onPrimary = palette.surface,
        secondaryContainer = palette.chip, onSecondaryContainer = palette.accent,
        surface = palette.surface, onSurface = palette.onSurface,
        surfaceVariant = palette.chip, onSurfaceVariant = palette.onSurfaceMuted,
        outline = palette.onSurfaceFaded.copy(alpha = 0.4f),
    )) {
      Column(Modifier.fillMaxWidth()) {
        ChipRow(Modifier.padding(horizontal = 14.dp)) {
            FilterChip(selected = appearance, onClick = { onAppearanceChange(true) }, label = { Text(uiText(UiR.string.ui_appearance_41def)) })
            FilterChip(selected = !appearance, onClick = { onAppearanceChange(false) }, label = { Text(uiText(UiR.string.ui_cc_translation_508d4)) })
        }
        androidx.compose.runtime.key(appearance) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (appearance) {
            SliderRow(uiText(UiR.string.ui_height_3f608), (cfg.bubbleHeightDp?.toFloat() ?: currentHeightDp).coerceIn(144f, 600f),
                144f..600f, palette) { value -> onConfigChange { it.copy(bubbleHeightDp = value.toInt()) } }
            ChipRow {
                listOf(uiText(UiR.string.ui_compact_1df39) to 160, uiText(UiR.string.ui_comfortable_23137) to 240, uiText(UiR.string.ui_large_738fd) to 320).forEach { (label, size) ->
                    FilterChip(selected = cfg.bubbleHeightDp == size,
                        onClick = { onConfigChange { it.copy(bubbleHeightDp = size) } }, label = { Text(label) })
                }
            }
        SettingsLabel(uiText(UiR.string.ui_text_size_3cc6e), palette)
        ChipRow {
            CaptionFontScale.entries.forEach { scale ->
                FilterChip(
                    selected = cfg.fontScale == scale,
                    onClick = { onConfigChange { it.copy(fontScale = scale) } },
                    label = { Text(uiText.label(scale), fontSize = 11.sp) },
                )
            }
        }

        SettingsLabel(uiText(UiR.string.ui_theme_a797e), palette)
        ChipRow {
            CaptionTheme.entries.forEach { theme ->
                FilterChip(
                    selected = cfg.theme == theme,
                    onClick = { onConfigChange { it.copy(theme = theme) } },
                    label = { Text(uiText.label(theme), fontSize = 11.sp) },
                )
            }
        }

        SettingsLabel(uiText(UiR.string.ui_position_cf1c8), palette)
        ChipRow {
            CaptionAnchor.entries.forEach { anchor ->
                FilterChip(
                    selected = cfg.anchor == anchor,
                    onClick = { onConfigChange { it.copy(anchor = anchor, xOffsetPx = 0, yOffsetPx = 0) } },
                    label = { Text(uiText.label(anchor), fontSize = 11.sp) },
                )
            }
            // Recovery from the panel too: snap a dragged bubble back to its
            // anchored default (same action as the notification button).
            FilterChip(
                selected = false,
                onClick = { onConfigChange { it.copy(xOffsetPx = 0, yOffsetPx = 0) } },
                label = { Text(uiText(UiR.string.ui_re_center_a540d), fontSize = 11.sp) },
            )
        }

        SliderRow(
            label = uiText(UiR.string.ui_width_1_s_f9552, cfg.widthPercent),
            value = cfg.widthPercent.toFloat(),
            range = 50f..100f,
            palette = palette,
        ) { value -> onConfigChange { it.copy(widthPercent = value.toInt()) } }

        SliderRow(
            label = uiText(UiR.string.ui_background_1_s_6f4b0, cfg.backgroundOpacity),
            value = cfg.backgroundOpacity.toFloat(),
            range = 0f..100f,
            palette = palette,
        ) { value -> onConfigChange { it.copy(backgroundOpacity = value.toInt()) } }

        SettingsLabel(uiText(UiR.string.ui_previous_segments_1_s_5e486, cfg.historyLines), palette)
        ChipRow {
            listOf(0, 1, 2, 4, 8).forEach { lines ->
                FilterChip(
                    selected = cfg.historyLines == lines,
                    onClick = { onConfigChange { it.copy(historyLines = lines) } },
                    label = { Text("$lines", fontSize = 11.sp) },
                )
            }
        }

        ToggleRow(
            label = uiText(UiR.string.ui_show_live_partial_text_27286),
            checked = cfg.showPartial,
            palette = palette,
        ) { checked -> onConfigChange { it.copy(showPartial = checked) } }

        ToggleRow(
            label = uiText(UiR.string.ui_tap_through_keep_unlock_handle_a2050),
            checked = cfg.tapThrough,
            palette = palette,
        ) { checked -> onConfigChange { it.copy(tapThrough = checked) } }

        } else {
        SettingsLabel(uiText(UiR.string.ui_mode_a7b93), palette)
        ChipRow {
            CaptionMode.entries.forEach { mode ->
                FilterChip(
                    selected = cfg.mode == mode,
                    onClick = { onConfigChange { it.withCaptionMode(mode) } },
                    label = { Text(uiText.label(mode), fontSize = 11.sp, maxLines = 1) },
                )
            }
        }

        if (cfg.effectiveEngine.speechBackend != null) {
            ToggleRow(uiText(UiR.string.ui_text_translation_9f474), cfg.localTranslationEnabled, palette) { enabled ->
                onConfigChange { it.copy(localTranslationEnabled = enabled,
                    mode = if (enabled) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS) }
            }
        }

        com.sal7one.transiber.translation.CaptionTranslatorChooser(cfg, onConfigChange, { openSetup(1) })
        CaptionLanguageFields(cfg, onConfigChange, onOpenOverlay = { which ->
            onConfigChange { it.copy(languagePicker = which) }
        })
        if (cfg.mode == CaptionMode.TRANSLATE) {
            SettingsLabel(uiText(UiR.string.ui_show_d97d1), palette)
            ChipRow {
                CaptionDisplay.entries.forEach { display ->
                    FilterChip(
                        selected = cfg.display == display,
                        onClick = { onConfigChange { it.copy(display = display) } },
                        label = { Text(uiText.label(display), fontSize = 11.sp, maxLines = 1) },
                    )
                }
            }

        }

            SettingsLabel(uiText(UiR.string.ui_current_speech_model_29c90), palette)
            Text(uiText.label(cfg.effectiveEngine), color = palette.onSurface)
            TextButton(onClick = { openSetup(1) }) { Text(uiText(UiR.string.ui_choose_or_download_models_a1983)) }
            TextButton(onClick = { openSetup(3) }) { Text(uiText(UiR.string.ui_full_setup_cloud_settings_ac020)) }
            TextButton(onClick = onClear) { Text(uiText(UiR.string.ui_clear_transcript_05690)) }
            TextButton(onClick = { onShowReadAloudChange(!showReadAloud) }) { Text(if (showReadAloud) uiText(UiR.string.ui_hide_read_aloud_options_c6763) else uiText(UiR.string.ui_read_aloud_options_dc13d)) }
            if (showReadAloud) {
        // ── Voice output (TTS) ────────────────────────────────────────────
        // Device voice works everywhere (offline with installed voices);
        // Neural needs an imported ONNX voice model; Cloud is NETWORK
        // CODE (BYOK, play distribution only — byok/CloudTtsSpeaker).
        ToggleRow(
            label = uiText(UiR.string.ui_speak_captions_47294),
            checked = cfg.speakCaptions,
            palette = palette,
        ) { checked -> onConfigChange { it.copy(speakCaptions = checked) } }
        TextButton(onClick = { openSetup(12) }) { Text(uiText(UiR.string.ui_read_aloud_voices_downloads_7b18c)) }
        if (cfg.speakCaptions) {
            val speakerContext = LocalContext.current
            val voiceReady = com.sal7one.transiber.voice.VoiceModels(java.io.File(speakerContext.filesDir, "voice-models")).ready(com.sal7one.transiber.voice.VoiceSettings.choice(speakerContext).voice)
            ChipRow {
                CaptionSpeakerChoice.entries
                    .filter { it != CaptionSpeakerChoice.CLOUD || ByokPolicy.FEATURE_BYOK }
                    .forEach { choice ->
                        FilterChip(
                            selected = cfg.speakerChoice == choice,
                            onClick = { onConfigChange { it.copy(speakerChoice = choice) } },
                            // Native speech requires the verified engine and selected voice.
                            enabled = choice != CaptionSpeakerChoice.NATIVE || voiceReady,
                            label = { Text(uiText.label(choice), fontSize = 11.sp) },
                        )
                    }
            }
            SettingsCaption(
                if (cfg.speakerChoice == CaptionSpeakerChoice.NATIVE && !voiceReady) {
                    uiText(UiR.string.ui_install_supertonic_3_and_a_voice_in_read_aloud_settings_8a8c5)
                } else {
                    uiText.explanation(cfg.speakerChoice)
                },
                palette,
            )
        }

            }
        }
        Spacer(Modifier.height(8.dp))
        }
        }
      }
    }
}

@Composable
private fun PresetRow(
    cfg: CaptionOverlayConfig,
    palette: OverlayPalette,
    onConfigChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
) {
    val selectedId = CaptionPresets.selected(cfg)?.id
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(CaptionPresets.ALL, key = { it.id }) { preset ->
            PresetCard(
                preset = preset,
                selected = preset.id == selectedId,
                palette = palette,
                onClick = { onConfigChange { preset.applyTo(it) } },
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: CaptionPreset,
    selected: Boolean,
    palette: OverlayPalette,
    onClick: () -> Unit,
) {
    val uiText = rememberUiText()

    Surface(
        onClick = onClick,
        modifier = Modifier.width(132.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.chip else palette.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) palette.accent else palette.onSurfaceFaded.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = uiText.label(preset),
                color = if (selected) palette.accent else palette.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Text(
                text = preset.description,
                color = palette.onSurfaceFaded,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun SettingsLabel(text: String, palette: OverlayPalette) {
    Text(text = text, color = palette.accent, fontSize = 11.sp)
}

@Composable
private fun SettingsCaption(text: String, palette: OverlayPalette) {
    Text(text = text, color = palette.onSurfaceFaded, fontSize = 10.sp)
}

@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    palette: OverlayPalette,
    onChange: (Float) -> Unit,
) {
    Column {
        SettingsLabel(label, palette)
        // Local drag state: committing per tick would write DataStore and
        // re-emit the whole config flow ~60x/s for the whole drag.
        var local by remember(value) { mutableFloatStateOf(value) }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.onSurfaceFaded.copy(alpha = 0.35f),
                activeTickColor = palette.surface,
                inactiveTickColor = palette.onSurfaceFaded.copy(alpha = 0.35f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp).semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    palette: OverlayPalette,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = palette.onSurface,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
