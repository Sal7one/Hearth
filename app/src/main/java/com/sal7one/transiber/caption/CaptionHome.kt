package com.sal7one.transiber.caption

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
        else -> ApiKeyStore.hasOpenAiKey(context)
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
            Text(if (running) "Captions are running. Open the bubble for live controls, or stop to change setup."
                else "What would you like to hear?",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            HomeChips("Audio", if (cfg.source == CaptionSource.MIC) "Microphone" else "Device audio",
                listOf("Device audio" to CaptionSource.PLAYBACK_CAPTURE, "Microphone" to CaptionSource.MIC), enabled = !running) {
                update { c -> c.copy(source = it) }
            }
            HomeChips("Show", if (cfg.mode == CaptionMode.CAPTIONS) "Original captions" else "Translation",
                listOf("Original captions" to CaptionMode.CAPTIONS, "Translation" to CaptionMode.TRANSLATE), enabled = !running) { mode ->
                update { it.copy(mode = mode, localTranslationEnabled = mode == CaptionMode.TRANSLATE && (it.effectiveEngine.speechBackend != null || it.textTranslationProviderId.isNotBlank())) }
            }
            }
            Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp)) {
                CaptionLanguageFields(cfg, ::update, enabled = !running, compact = true)
            }
            FeatureAction("Speech & translation", Icons.Default.Tune, { options = true }, detail = if (isCloud) "${cfg.effectiveEngine.label} · audio sent to your provider" else "${cfg.effectiveEngine.label} · on this phone")
            if (!engineReady) Text(if (isCloud) "Connect your cloud provider to start." else "Choose a speech model to start.")
            if (needsTranslator) TextButton(onClick = { options = true }) { Text("Set up translation") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }

        }
        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.65f),shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(modifier = Modifier.weight(1f).heightIn(min = 52.dp), onClick = {
                    when {
                        running -> CaptionCaptureService.show(context)
                        !engineReady -> if (isCloud) onCloud() else onModels()
                        else -> scope.launch {
                            pendingWrite?.join()
                            if (!saveFailed) CaptionStartActivity.start(context, config!!.source)
                        }
                    }
                }) { Text(when { running -> "Show captions"; !engineReady && isCloud -> "Connect cloud"; !engineReady -> "Choose a model"; needsTranslator -> "Start CC"; else -> "Start captions" }) }
                if (running) IconButton(onClick = { CaptionCaptureService.stop(context) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.Stop, "Stop captions")
                }
            }
        }
    }
    if (options) FeatureOptionsSheet("Caption settings", { options = false }, optionsScroll) {
        val engines = CaptionEngineChoice.entries.filter { it != CaptionEngineChoice.CLOUD || ByokPolicy.FEATURE_BYOK }
        HomeChoice("Speech engine", cfg.effectiveEngine.label, engines.map { it.label to it }, enabled = !running) { engine ->
            if (engine != cfg.effectiveEngine) update { it.copy(engine = engine, modelId = "", localTranslationEnabled = it.mode == CaptionMode.TRANSLATE && (engine.speechBackend != null || it.textTranslationProviderId.isNotBlank())) }
        }
        Text(if (isCloud) "Audio goes to your configured cloud provider." else "Speech stays on this phone.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { options = false; if (isCloud) onCloud() else onModels() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isCloud) "Manage speech connection" else "Choose or download speech models")
        }
        if (cfg.mode == CaptionMode.TRANSLATE) {
            HorizontalDivider()
            com.sal7one.transiber.translation.CaptionTranslatorChooser(cfg, ::update, { options = false; onModels() }, enabled = !running)
        }
        if (running) Text("Stop captions to change setup.", style = MaterialTheme.typography.bodySmall)
        if (cfg.source == CaptionSource.PLAYBACK_CAPTURE) Text("Some apps block audio capture. Use Microphone if captions stay silent.", style = MaterialTheme.typography.bodySmall)
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
