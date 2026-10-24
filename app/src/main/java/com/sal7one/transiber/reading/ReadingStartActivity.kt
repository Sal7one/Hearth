package com.sal7one.transiber.reading

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionCaptureService
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.ocr.*
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsOcrLocalUi
import com.sal7one.transiber.shortcuts.OverlaySetupVisibility
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import com.sal7one.transiber.ui.theme.FFmpegStudioTheme
import com.sal7one.transiber.ui.theme.FeatureBackdrop
import com.sal7one.transiber.ui.theme.rememberThemeMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingStartActivity : AppCompatActivity() {
    private var setupRevision by mutableIntStateOf(0)
    private var visibilityLease: AutoCloseable? = null
    override fun onResume() { super.onResume(); setupRevision++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FFmpegStudioTheme(themeMode = rememberThemeMode()) { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        if (visibilityLease == null) visibilityLease = OverlaySetupVisibility.acquire()
    }

    override fun onStop() {
        visibilityLease?.close(); visibilityLease = null
        super.onStop()
    }

    override fun onDestroy() {
        visibilityLease?.close(); visibilityLease = null
        super.onDestroy()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Screen() {
        val text = rememberUiText()
        val scope = rememberCoroutineScope()
        val currentSetup = setupRevision
        val bookmarks = remember { UserPreferences.store(this, "reading-overlay") }
        val (settings, settingsStore) = rememberReadingOverlaySettings()
        val (selection, selectionStore) = rememberOcrPreferences()
        val config by remember { CaptionConfigStore.config(this) }.collectAsState(initial = CaptionOverlayConfig())
        val revision by ConversationTranslationSettings.revision.collectAsState()
        val running by ReadingOverlayService.running.collectAsState()
        val localModelId = selection.translationModel(config.localTranslationModelId)
        val cloud = remember(selection.providerId, revision, currentSetup) {
            ConversationTranslationSettings.provider(selection.providerId)?.let {
                ConversationTranslationSettings.capabilities(this, it)
            }
        }
        val targets = ocrTranslationTargets(selection, localModelId, cloud, ByokPolicy.FEATURE_BYOK)
        val translator = ConversationTranslationSettings.provider(selection.providerId)?.label ?: TranslationOptions.label(localModelId)
        val translationIssue = text.ocrTranslationIssue(selection, localModelId, cloud, ByokPolicy.FEATURE_BYOK)
        val profile = selection.profile
        val content = ReadingContent.selected(selection)
        var readerReady by remember(profile.id) { mutableStateOf(false) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var starting by remember { mutableStateOf(false) }
        var sheet by rememberSaveable { mutableStateOf<String?>(null) }
        var advanced by rememberSaveable { mutableStateOf(false) }
        val pageScroll = rememberScrollState()
        val optionsScroll = rememberScrollState()
        val modelsScroll = rememberScrollState()
        val translatorScroll = rememberScrollState()

        LaunchedEffect(profile.id, selection.source) {
            bookmarks.edit().putString("${content.key}-profile", profile.id)
                .putString("${content.key}-source", selection.source).apply()
        }
        LaunchedEffect(profile.id, currentSetup) {
            val installed = OcrModels(File(filesDir, "ocr-models"))
            while (isActive) {
                readerReady = withContext(Dispatchers.IO) { installed.ready(profile) }
                delay(1_500)
            }
        }
        BackHandler(enabled = sheet != null) { sheet = null }

        fun recover() {
            startService(Intent(this, ReadingOverlayService::class.java).setAction("recover"))
            finish()
        }
        val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                if (starting) return@rememberLauncherForActivityResult
                starting = true; error = null
                scope.launch {
                    try {
                        CaptionCaptureService.stop(this@ReadingStartActivity)
                        ReadingOverlayService.stopAndAwait(this@ReadingStartActivity)
                        com.sal7one.transiber.runtime.LocalWorkGate.awaitIdle()
                        androidx.core.content.ContextCompat.startForegroundService(this@ReadingStartActivity,
                            Intent(this@ReadingStartActivity, ReadingOverlayService::class.java).putExtra("projection", result.data))
                        finish()
                    } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                        error = text(R.string.pipeline_cleanup_timeout)
                    } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
                    catch (failure: Exception) { error = failure.message ?: failure.toString() }
                    finally { starting = false }
                }
            } else error = text(R.string.ui_screen_sharing_was_cancelled_tap_start_reading_when_ready_b7b72)
        }
        fun launchCapture() {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                error = text(R.string.ui_allow_hearth_to_display_over_other_apps_return_here_then_tap_star_1c196)
                return
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection.launch(if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { launchCapture() }
        fun startReading() {
            error = null
            if (running) { recover(); return }
            if (!OcrModels(File(filesDir, "ocr-models")).ready(profile)) {
                error = text(R.string.ui_install_1_s_to_read_this_language_70476, text.label(profile)); sheet = "models"
            } else if (translationIssue != null) { error = translationIssue; sheet = "translator" }
            else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else launchCapture()
        }

        FeatureBackdrop(R.drawable.home_screen) {
            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text(R.string.ui_back_b52b3)) }
                        Text(text(R.string.tile_reading), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { sheet = "options" }) { Icon(Icons.Default.Tune, text(R.string.reading_setup_options)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(pageScroll).padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(painterResource(R.drawable.screen_translation_hero), null,
                                Modifier.fillMaxWidth().height(104.dp), contentScale = ContentScale.Fit)
                            Text(text(R.string.reading_setup_title), style = MaterialTheme.typography.headlineMedium)
                            Text(text(R.string.reading_setup_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (running) {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(text(R.string.reading_setup_active), style = MaterialTheme.typography.titleMedium)
                                    Text(text(R.string.reading_setup_active_help), style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(onClick = { recover() }) { Text(text(R.string.reading_setup_return)) }
                                        TextButton(onClick = { stopService(Intent(this@ReadingStartActivity, ReadingOverlayService::class.java)) }) { Text(text(R.string.reading_setup_stop)) }
                                    }
                                }
                            }
                        }
                        ReadingSetupSection(text(R.string.reading_content_kind)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ReadingContent.entries.forEach { next ->
                                    val chosen = content == next
                                    OutlinedCard(onClick = {
                                        try {
                                            bookmarks.edit().putString("${content.key}-profile", profile.id)
                                                .putString("${content.key}-source", selection.source).apply()
                                            selectionStore.update { it.forReadingContent(next,
                                                bookmarks.getString("${next.key}-profile", null), bookmarks.getString("${next.key}-source", null)) }
                                            error = null
                                        } catch (failure: Exception) { error = failure.message ?: failure.toString() }
                                    }, modifier = Modifier.weight(1f).heightIn(min = 104.dp).semantics { selected = chosen },
                                        colors = CardDefaults.outlinedCardColors(containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                                        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Icon(if (next == ReadingContent.WEBSITES) Icons.Default.Public else Icons.Default.AutoStories, null)
                                            Text(text(if (next == ReadingContent.WEBSITES) R.string.reading_content_websites else R.string.reading_content_manga), style = MaterialTheme.typography.titleSmall)
                                        }
                                    }
                                }
                            }
                        }
                        if (content == ReadingContent.MANGA) Text(text(R.string.ocr_reader_capture_only),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ReadingSetupSection(text(R.string.reading_setup_languages)) {
                            OcrLanguageControls(selection, targets, translator,
                                { next -> selectionStore.update { next }; error = null }, { sheet = "models" },
                                showMangaModes = content == ReadingContent.MANGA,
                                onTranslator = { sheet = "translator" }, translationIssue = translationIssue)
                            if (profile.engine == "manga") Text(text(R.string.ui_manga_ocr_needs_a_drawn_area_around_one_speech_bubble_214c9),
                                style = MaterialTheme.typography.bodySmall)
                            if (!readerReady) {
                                Text(text(R.string.ocr_reader_needs_files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = { sheet = "models" }) { Text(text(R.string.ui_get_ocr_models_245d8)) }
                            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(text(R.string.reading_setup_reader_ready), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ReadingSetupSection(text(R.string.reading_setup_refresh)) {
                            listOf("manual" to (R.string.reading_setup_manual to R.string.reading_setup_manual_help),
                                "page" to (R.string.reading_setup_auto to R.string.reading_setup_auto_help)).forEach { (mode, labels) ->
                                OutlinedCard(onClick = { settingsStore.update { it.copy(mode = mode) } },
                                    modifier = Modifier.fillMaxWidth().semantics { selected = settings.mode == mode },
                                    colors = CardDefaults.outlinedCardColors(containerColor = if (settings.mode == mode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        RadioButton(selected = settings.mode == mode, onClick = null)
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(text(labels.first), style = MaterialTheme.typography.titleSmall)
                                            Text(text(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        FeatureAction(text(R.string.reading_setup_options), Icons.Default.Tune, { sheet = "options" },
                            detail = text(R.string.reading_setup_options_detail))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text(R.string.reading_setup_controls_hint), style = MaterialTheme.typography.bodySmall)
                            Text(text(R.string.reading_capture_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text(R.string.ui_starting_screen_translation_stops_live_audio_captions_17e29), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (starting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(text(R.string.pipeline_waiting_cleanup))
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Surface(shadowElevation = 6.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !starting, onClick = { startReading() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(text(if (running) R.string.reading_setup_return else R.string.ui_start_reading_overlay_c2be1))
                            }
                            Text(text(R.string.reading_setup_saved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        when (sheet) {
            "models" -> FeatureOptionsSheet(text(R.string.ui_ocr_reader_2de99), { sheet = null }, modelsScroll) {
                SettingsOcrLocalUi(selected = selection.profileId,
                    onSelect = { id -> selectionStore.update { it.withProfile(id) }; error = null; sheet = null },
                    onDownloads = { startActivity(Intent(this@ReadingStartActivity, com.sal7one.transiber.MainActivity::class.java)
                        .putExtra("page", 2).putExtra("returnToReading", true)) })
            }
            "translator" -> FeatureOptionsSheet(text(R.string.reading_setup_translator), { sheet = null }, translatorScroll) {
                TranslatorChooser(selection.providerId, localModelId, text(R.string.reading_setup_shared_translator), selection.source, selection.target,
                    onModels = {
                        UserPreferences.store(this@ReadingStartActivity, "translation-browser").edit().putBoolean("open", true).apply()
                        startActivity(Intent(this@ReadingStartActivity, com.sal7one.transiber.MainActivity::class.java)
                            .putExtra("page", 1).putExtra("returnToReading", true).putExtra("ocrTranslationSelection", true))
                    }, initiallyExpanded = true, onSelect = { id, model ->
                        try { selectionStore.update { it.withTranslator(id, model) }; error = null }
                        catch (failure: Exception) { error = failure.message ?: failure.toString() }
                    })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            "options" -> FeatureOptionsSheet(text(R.string.reading_setup_options), { sheet = null }, optionsScroll) {
                ReadingSetupSection(text(R.string.reading_setup_display)) {
                    ReadingOptionSwitch(text(R.string.reading_passthrough), text(R.string.reading_setup_passthrough_help), settings.passThrough) { value ->
                        settingsStore.update { it.copy(passThrough = value) }
                    }
                    ReadingOptionSwitch(text(R.string.reading_setup_compact), text(R.string.reading_setup_compact_help), settings.compactHandle) { value ->
                        settingsStore.update { it.copy(compactHandle = value) }
                    }
                    ReadingOptionSwitch(text(R.string.reading_setup_show_translation), text(R.string.reading_setup_show_translation_help), settings.translationVisible) { value ->
                        settingsStore.update { it.copy(translationVisible = value) }
                    }
                    ReadingOptionSwitch(text(R.string.reading_setup_show_original), text(R.string.reading_setup_show_original_help), settings.showOriginal) { value ->
                        settingsStore.update { it.copy(showOriginal = value) }
                    }
                    Text(text(R.string.reading_setup_text_size, settings.textSizeSp.toInt()), style = MaterialTheme.typography.titleSmall)
                    Slider(settings.textSizeSp, { value -> settingsStore.update { it.copy(textSizeSp = value) } },
                        valueRange = 14f..34f, steps = 9,
                        modifier = Modifier.semantics { contentDescription = text(R.string.reading_setup_text_size_label) })
                }
                ReadingStyleOptions(settings) { change -> settingsStore.update(change) }
                TextButton(onClick = { advanced = !advanced }) { Text(text(if (advanced) R.string.reading_setup_hide_advanced else R.string.reading_setup_advanced)) }
                if (advanced) ReadingSetupSection(text(R.string.reading_setup_auto_tuning)) {
                    Text(text(R.string.reading_setup_auto_tuning_help), style = MaterialTheme.typography.bodySmall)
                    Text(text(R.string.reading_setup_sensitivity), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("stable" to R.string.reading_setup_stable, "balanced" to R.string.reading_setup_balanced,
                            "sensitive" to R.string.reading_setup_sensitive).forEach { (value, label) ->
                            FilterChip(settings.changeSensitivity == value, { settingsStore.update { it.copy(changeSensitivity = value) } }, label = { Text(text(label)) })
                        }
                    }
                    Text(text(R.string.reading_setup_sensitivity_help), style = MaterialTheme.typography.bodySmall)
                    ReadingTimingSlider(text(R.string.reading_setup_settle, settings.settleMs.toInt()), settings.settleMs,
                        500f..2_500f, 19) { value -> settingsStore.update { it.copy(settleMs = value) } }
                    ReadingTimingSlider(text(R.string.reading_setup_cooldown, settings.refreshCooldownMs.toInt()), settings.refreshCooldownMs,
                        2_000f..15_000f, 12) { value -> settingsStore.update { it.copy(refreshCooldownMs = value) } }
                    ReadingTimingSlider(text(R.string.reading_setup_scan, settings.scanMs.toInt()), settings.scanMs,
                        250f..1_500f, 4) { value -> settingsStore.update { it.copy(scanMs = value) } }
                    Text(text(R.string.reading_setup_battery), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun ReadingSetupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable private fun ReadingOptionSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable private fun ReadingTimingSlider(label: String, value: Long, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Slider(value.toFloat(), { onChange(it.toLong()) }, valueRange = range, steps = steps,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
