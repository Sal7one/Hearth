package com.sal7one.transiber.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
fun ModelsScreen() {
 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 val registry = remember { ModelRegistry.getInstance(context) }
 val models by registry.registeredModels.collectAsStateWithLifecycle()
 var config by remember { mutableStateOf(CaptionOverlayConfig(engine = CaptionEngineChoice.QWEN)) }
 var importRevision by remember { mutableIntStateOf(0) }
 var busy by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf<String?>(null) }
 LaunchedEffect(Unit) { try { config = CaptionConfigStore.config(context).first().let { if (it.engine == CaptionEngineChoice.QWEN || it.engine == CaptionEngineChoice.NEMOTRON) it else it.copy(engine = CaptionEngineChoice.QWEN) }; registry.refreshModels() } catch(e: Exception) { message = e.message ?: e.toString() } }
 fun import(uri: android.net.Uri, directory: Boolean) {
  scope.launch {
   busy = true; message = "Copying and verifying model… Keep this screen open."
   try {
    val name = if (directory) "" else androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
    if (!directory && name.endsWith(".zip", ignoreCase = true)) {
     val imported = withContext(Dispatchers.IO) {
      val job = currentCoroutineContext()
      context.contentResolver.openInputStream(uri)?.use { input ->
       LocalSpeechModels(java.io.File(context.filesDir, "speech-models")).importZip(input) { job.ensureActive() }
      } ?: error("Cannot open selected model ZIP")
     }
     config = config.copy(engine = imported.profile.captionEngine, modelId = imported.id, mode = CaptionMode.CAPTIONS, streamLanguage = "auto")
     CaptionConfigStore.update(context) { it.copy(engine = config.engine, modelId = config.modelId, mode = config.mode, streamLanguage = config.streamLanguage, localTranslationEnabled = config.localTranslationEnabled, localTranslationModelId = config.localTranslationModelId, target = config.target) }
     importRevision++
     message = "Imported ${imported.profile.label}. Selected for original-language captions."
     return@launch
    }
    val model = if (directory) registry.importModelDirectory(uri) else registry.importModel(uri)
    message = model?.let { "Imported ${it.name}. Select it in Captions." } ?: "Unsupported model structure. Use a Whisper .bin file, Vosk folder, or Marian translation folder."
   } catch(e: CancellationException) { throw e }
   catch(e: Exception) { message = generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() } }
   finally { busy = false }
  }
 }
 val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { import(it, false) } }
 val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { import(it, true) } }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Text("Local speech models", style = MaterialTheme.typography.headlineSmall)
  Text("Model weights are separate downloads. The runtimes are included. Imported files stay on this device.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   listOf(CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON).forEach { engine ->
    FilterChip(selected = config.engine == engine, onClick = { config = config.copy(engine = engine, modelId = "") }, label = { Text(engine.label) })
   }
  }
  key(importRevision) { LocalSpeechSetup(config, update = { transform ->
   config = transform(config)
   scope.launch { CaptionConfigStore.update(context) { current -> current.copy(engine = config.engine, modelId = config.modelId, mode = config.mode, streamLanguage = config.streamLanguage, localTranslationEnabled = config.localTranslationEnabled, localTranslationModelId = config.localTranslationModelId, target = config.target) } }
  }, onModelsChanged = {}) }
  Text("Qwen / Nemotron packages must contain hearth-speech.json and the verified model files. The repository includes a package builder and source links. Raw model downloads need packaging first.")
  HorizontalDivider()
  Text("Whisper, Vosk and translation", style = MaterialTheme.typography.titleLarge)
  Button(onClick = { file.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import model file or speech ZIP") }
  OutlinedButton(onClick = { folder.launch(null) }, enabled = !busy) { Text("Import Vosk / translation folder") }
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  message?.let { Text(it) }
  models.forEach { model ->
   Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
    Text(model.name, style = MaterialTheme.typography.titleMedium)
    Text("${model.engineType.displayName} · ${model.sizeBytes / 1_048_576} MiB · ${if(model.isValid) "Verified" else "Needs attention"}")
   } }
  }
  Text("Local Qwen and Nemotron provide original-language CC. The optional local translation bridge adds text translation; Whisper can translate speech to English, and Marian supports installed language pairs.")
 }
}
