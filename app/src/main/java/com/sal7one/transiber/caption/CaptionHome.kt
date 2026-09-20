package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import com.sal7one.transiber.ui.theme.glassPanel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.saveable.rememberSaveable
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.semantics.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.models.*
import kotlinx.coroutines.*
import java.io.File

/** Everyday controls only. Installation and provider configuration belong to Setup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionHome(
    onModels: () -> Unit,
    onCloud: () -> Unit,
) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var options by rememberSaveable { mutableStateOf(false) }
    val optionsScroll = rememberScrollState()
    val registry = remember { ModelRegistry.getInstance(context) }
    val registered by registry.registeredModels.collectAsStateWithLifecycle()
    val running by CaptionCaptureService.running.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    var speech by remember { mutableStateOf<List<LocalSpeechModel>>(emptyList()) }
    var translations by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    var pendingWrite by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        CaptionConfigStore.config(context).collect { config = it }
    }
    LaunchedEffect(Unit) {
        try {
            registry.refreshModels()
            withContext(Dispatchers.IO) {
                speech = LocalSpeechModels(File(context.filesDir, "speech-models")).list()
                translations = com.sal7one.transiber.translation.LocalTranslationModels(File(context.filesDir, "translation-models"))
                    .installed().map { it.id }.toSet()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val cfg = config
    if (cfg == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    fun update(transform: (CaptionOverlayConfig) -> CaptionOverlayConfig) {
        config = transform(cfg)
        saveFailed = false
        val previous = pendingWrite
        pendingWrite = scope.launch {
            previous?.join()
            try { CaptionConfigStore.update(context, transform); saveFailed = false }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveFailed = true; error = e.message ?: e.toString() }
        }
    }
    val isCloud = cfg.effectiveEngine == CaptionEngineChoice.CLOUD
    val keyReady = if (!ByokPolicy.FEATURE_BYOK) false else when (CloudConfigStore.sttMode(context)) {
        CloudConfigStore.SttMode.STREAMING_SONIOX -> ApiKeyStore.getSonioxKey(context).isNotBlank()
        CloudConfigStore.SttMode.STREAMING_ELEVENLABS -> ApiKeyStore.getElevenLabsKey(context).isNotBlank()
        CloudConfigStore.SttMode.STREAMING_DEEPGRAM -> ApiKeyStore.getDeepgramKey(context).isNotBlank()
        CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI -> ApiKeyStore.getAssemblyAiKey(context).isNotBlank()
        else -> !com.sal7one.transiber.byok.cloudSpeechKeyRequired(CloudConfigStore.sttMode(context),CloudConfigStore.provider(context)) || ApiKeyStore.hasOpenAiKey(context)
    }
    val engineReady = when {
        isCloud -> keyReady
        cfg.effectiveEngine.speechBackend != null -> speech.any {
            it.profile.backend == cfg.effectiveEngine.speechBackend && (cfg.modelId.isBlank() || cfg.modelId == it.id)
        }
        else -> registered.any { it.isValid && (cfg.modelId.isBlank() || cfg.modelId == it.id) &&
            it.engineType == if (cfg.effectiveEngine == CaptionEngineChoice.VOSK) ModelEngineType.VOSK else ModelEngineType.WHISPER }
    }
    val needsTranslator = when (captionTranslationRoute(cfg, CloudConfigStore.sttMode(context))) {
        CaptionTranslationRoute.TEXT_TRANSLATOR -> if (cfg.textTranslationProviderId !in setOf("", "local")) !com.sal7one.transiber.translation.captionCloudTranslatorReady(cfg) else if (cfg.localTranslationModelId == com.sal7one.transiber.translation.TranslationOptions.ML_KIT)
            !com.sal7one.transiber.translation.PlatformTranslation.available else cfg.localTranslationModelId !in translations
        CaptionTranslationRoute.UNSUPPORTED -> true
        CaptionTranslationRoute.ENGLISH_PIVOT, CaptionTranslationRoute.ENGLISH_TEXT ->
            !registered.any { it.isValid && it.engineType == ModelEngineType.TRANSLATE }
        else -> false
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (running) uiText(UiR.string.ui_captions_are_running_open_the_bubble_for_live_controls_or_stop_to_12914)
                else uiText(UiR.string.ui_what_would_you_like_to_hear_dba27),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            HomeChips(uiText(UiR.string.ui_audio_acdac), if (cfg.source == CaptionSource.MIC) uiText(UiR.string.ui_microphone_24280) else uiText(UiR.string.ui_device_audio_c5702),
                listOf(uiText(UiR.string.ui_device_audio_c5702) to CaptionSource.PLAYBACK_CAPTURE, uiText(UiR.string.ui_microphone_24280) to CaptionSource.MIC), enabled = !running) {
                update { c -> c.copy(source = it) }
            }
            HomeChips(uiText(UiR.string.ui_show_d97d1), if (cfg.mode == CaptionMode.CAPTIONS) uiText(UiR.string.ui_original_captions_9d268) else uiText(UiR.string.ui_translation_ac26a),
                listOf(uiText(UiR.string.ui_original_captions_9d268) to CaptionMode.CAPTIONS, uiText(UiR.string.ui_translation_ac26a) to CaptionMode.TRANSLATE), enabled = !running) { mode ->
                update { it.withCaptionMode(mode) }
            }
            }
            Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp)) {
                CaptionLanguageFields(cfg, ::update, enabled = !running, compact = true)
            }
            FeatureAction(uiText(UiR.string.ui_speech_translation_8002b), Icons.Default.Tune, { options = true }, detail = if (isCloud) uiText(UiR.string.ui_1_s_audio_sent_to_your_provider_db1f9, uiText.label(cfg.effectiveEngine)) else uiText(UiR.string.ui_1_s_on_this_phone_0832f, uiText.label(cfg.effectiveEngine)))
            if (!engineReady) Text(if (isCloud) uiText(UiR.string.ui_connect_your_cloud_provider_to_start_14c5d) else uiText(UiR.string.ui_choose_a_speech_model_to_start_53e5a))
            if (needsTranslator) TextButton(onClick = { options = true }) { Text(uiText(UiR.string.ui_set_up_translation_1d214)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }

        }
        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.65f),shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(modifier = Modifier.weight(1f).heightIn(min = 52.dp), onClick = {
                    when {
                        running -> CaptionCaptureService.show(context)
                        !engineReady -> if (isCloud) onCloud() else onModels()
                        needsTranslator -> options = true
                        else -> scope.launch {
                            pendingWrite?.join()
                            if (!saveFailed) CaptionStartActivity.start(context, config!!.source)
                        }
                    }
                }) { Text(when { running -> uiText(UiR.string.ui_show_captions_78946); !engineReady && isCloud -> uiText(UiR.string.ui_connect_cloud_663c4); !engineReady -> uiText(UiR.string.ui_choose_a_model_fefb3); needsTranslator -> uiText(UiR.string.ui_set_up_translation_1d214); else -> uiText(UiR.string.ui_start_captions_68e1c) }) }
                if (running) IconButton(onClick = { CaptionCaptureService.stop(context) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.Stop, uiText(UiR.string.ui_stop_captions_58b64))
                }
            }
        }
    }
    if (options) FeatureOptionsSheet(uiText(UiR.string.ui_caption_settings_da0c8), { options = false }, optionsScroll) {
        val engines = CaptionEngineChoice.entries.filter { it != CaptionEngineChoice.CLOUD || ByokPolicy.FEATURE_BYOK }
        HomeChoice(uiText(UiR.string.ui_speech_engine_38c0a), uiText.label(cfg.effectiveEngine), engines.map { uiText.label(it) to it }, enabled = !running) { engine ->
            if (engine != cfg.effectiveEngine) update { it.copy(engine = engine, modelId = "", localTranslationEnabled = it.mode == CaptionMode.TRANSLATE && (engine.speechBackend != null || it.textTranslationProviderId.isNotBlank())) }
        }
        Text(if (isCloud) uiText(UiR.string.ui_audio_goes_to_your_configured_cloud_provider_77561) else uiText(UiR.string.ui_speech_stays_on_this_phone_c93d7), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { options = false; if (isCloud) onCloud() else onModels() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isCloud) uiText(UiR.string.ui_manage_speech_connection_1959e) else uiText(UiR.string.ui_choose_or_download_speech_models_34085))
        }
        if (cfg.mode == CaptionMode.TRANSLATE) {
            HorizontalDivider()
            com.sal7one.transiber.translation.CaptionTranslatorChooser(cfg, ::update, { options = false; onModels() }, enabled = !running)
        }
        if (running) Text(uiText(UiR.string.ui_stop_captions_to_change_setup_19d9d), style = MaterialTheme.typography.bodySmall)
        if (cfg.source == CaptionSource.PLAYBACK_CAPTURE) Text(uiText(UiR.string.ui_some_apps_block_audio_capture_use_microphone_if_captions_stay_sil_60ad2), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> HomeChoice(label: String, value: String, choices: List<Pair<String, T>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(value, modifier = Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (title, choice) -> DropdownMenuItem(
                    text = { Text(title) },
                    modifier = Modifier.semantics { selected = title == value },
                    onClick = { expanded = false; onSelect(choice) }) }
            }
        }
    }
}

/** Short choices stay visible, with standard selected-state and touch-target semantics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> HomeChips(label: String, value: String, choices: List<Pair<String, T>>, enabled: Boolean, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.forEach { (title, choice) ->
                FilterChip(selected = title == value, onClick = { onSelect(choice) },
                    enabled = enabled, label = { Text(title) })
            }
        }
    }
}
