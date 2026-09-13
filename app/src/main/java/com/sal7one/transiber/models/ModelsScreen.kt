package com.sal7one.transiber.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.*

@Composable
fun ModelsScreen(onCloud: () -> Unit = {}, onDownloads: () -> Unit = {}, onVoices: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(CaptionOverlayConfig()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("Speech") }
    var browsing by rememberSaveable { mutableStateOf(CaptionEngineChoice.NEMOTRON) }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect {
            config = it
            if (!loaded) { if (it.engine != CaptionEngineChoice.CLOUD) browsing = it.engine; loaded = true }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
        scope.launch { try { CaptionConfigStore.update(context, transform); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() } }
    }
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Speech", "Translation", "Camera", "Voices") + if (ByokPolicy.FEATURE_BYOK) listOf("Cloud") else emptyList()).forEach { group ->
                FilterChip(selected = section == group, onClick = { section = group }, label = { Text(group) })
            }
        }
        pages.SaveableStateProvider(section) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                when (section) {
                    "Voices" -> { Text("Android voices and local Supertonic 3, with verified downloads and optional self-hosted voices.");Button(onClick=onVoices){Text("Voices & read aloud")} }
                    "Speech" -> {
                        Text("Local speech recognition", style = MaterialTheme.typography.titleLarge)
                        Text("Choose a speech engine, then select an installed model or get its files. Translation is a separate choice.", style = MaterialTheme.typography.bodyMedium)
                        Text("Active speech: ${config.engine.label}", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                CaptionEngineChoice.NEMOTRON to "Nemotron",
                                CaptionEngineChoice.QWEN to "Qwen3-ASR",
                                CaptionEngineChoice.MOONSHINE to "Moonshine",
                                CaptionEngineChoice.WHISPER to "Whisper",
                                CaptionEngineChoice.VOSK to "Vosk",
                            ).forEach { (engine, label) ->
                                FilterChip(selected = browsing == engine, onClick = { browsing = engine },
                                    label = { Text(label) })
                            }
                        }
                        val shown = config.copy(engine = browsing, modelId = config.modelId.takeIf { config.engine == browsing }.orEmpty())
                        key(browsing) {
                            val select: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
                                val next = transform(shown)
                                browsing = next.engine
                                update { it.copy(engine = next.engine, modelId = next.modelId, mode = next.mode, streamLanguage = next.streamLanguage) }
                            }
                            if (browsing.speechBackend != null) LocalSpeechSetup(shown, select, includeTranslation = false, onModelsChanged = {})
                            else LegacyModelSetup(if (browsing == CaptionEngineChoice.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK, shown, select)
                        }
                    }
                    "Translation" -> {
                        Text("Local translation", style = MaterialTheme.typography.titleLarge)
                        Text("Language packs or text models for on-device translation.", style = MaterialTheme.typography.bodySmall)
                        com.sal7one.transiber.translation.LocalTranslationSetup(config, update, includeLegacy = true)
                    }
                    "Camera" -> com.sal7one.transiber.ocr.OcrModelSetup(onDownloads)
                    "Cloud" -> {
                        Text("Cloud speech & translation", style = MaterialTheme.typography.titleLarge)
                        Text("Cloud models run at your provider. They need an API key, not a model download.")
                        Button(onClick = onCloud, modifier = Modifier.fillMaxWidth()) { Text("Choose provider & manage keys") }
                        com.sal7one.transiber.byok.CloudSourceLinks()
                    }
                }
                if (ByokPolicy.FEATURE_BYOK && section != "Cloud") TextButton(onClick = onDownloads) { Text("View downloads & installation progress") }
            }
        }
    }
}
