package com.sal7one.transiber.caption

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
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
@Composable
fun CaptionHome(onModels: () -> Unit, onCloud: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        CaptionTranslationRoute.LOCAL_TEXT -> if (cfg.localTranslationModelId == com.sal7one.transiber.translation.TranslationOptions.ML_KIT)
            !com.sal7one.transiber.translation.PlatformTranslation.available else cfg.localTranslationModelId !in translations
        CaptionTranslationRoute.UNSUPPORTED -> true
        CaptionTranslationRoute.ENGLISH_PIVOT, CaptionTranslationRoute.ENGLISH_TEXT ->
            !registered.any { it.isValid && it.engineType == ModelEngineType.TRANSLATE }
        else -> false
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Read what you hear", style = MaterialTheme.typography.headlineSmall)
            if (running) Text("Captions are running. Use the bubble for live controls, or Stop to change setup.", style = MaterialTheme.typography.bodySmall)
            HomeChoice("Audio", if (cfg.source == CaptionSource.MIC) "Microphone" else "Device audio",
                listOf("Device audio" to CaptionSource.PLAYBACK_CAPTURE, "Microphone" to CaptionSource.MIC), enabled = !running) {
                update { c -> c.copy(source = it) }
            }
            HomeChoice("Show", if (cfg.mode == CaptionMode.CAPTIONS) "Original captions" else "Translation",
                listOf("Original captions" to CaptionMode.CAPTIONS, "Translation" to CaptionMode.TRANSLATE), enabled = !running) { mode ->
                update { it.copy(mode = mode, localTranslationEnabled = mode == CaptionMode.TRANSLATE && it.effectiveEngine.speechBackend != null) }
            }
            CaptionLanguageFields(cfg, ::update, enabled = !running)
            HorizontalDivider()
            val engines = CaptionEngineChoice.entries.filter { it != CaptionEngineChoice.CLOUD || ByokPolicy.FEATURE_BYOK }
            HomeChoice("Processing", cfg.effectiveEngine.label, engines.map { it.label to it }, enabled = !running) { engine ->
                if (engine != cfg.effectiveEngine) update { it.copy(engine = engine, modelId = "", localTranslationEnabled = it.mode == CaptionMode.TRANSLATE && engine.speechBackend != null) }
            }
            Text(if (isCloud) "Audio goes to your configured cloud provider." else "Audio and translation stay on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!engineReady) Text(if (isCloud) "Connect your cloud provider to start." else "Choose an installed speech model to start.")
            if (needsTranslator) TextButton(onClick = onModels) { Text("Choose a translation model · CC can start now") }
            if (cfg.source == CaptionSource.PLAYBACK_CAPTURE) Text("Some apps block audio capture. Use Microphone if captions stay silent.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Surface(shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
}

@Composable
private fun <T> HomeChoice(label: String, value: String, choices: List<Pair<String, T>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(value, modifier = Modifier.weight(1f))
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (title, choice) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(choice) }) }
            }
        }
    }
}
