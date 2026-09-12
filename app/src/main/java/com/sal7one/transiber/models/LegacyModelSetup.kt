package com.sal7one.transiber.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.*

@Composable
internal fun LegacyModelSetup(type: ModelEngineType, config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit) {
    val context = LocalContext.current
    val registry = remember { ModelRegistry.getInstance(context) }
    val models by registry.registeredModels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember(type) { mutableStateOf<String?>(null) }
    val name = when (type) { ModelEngineType.WHISPER -> "Whisper"; ModelEngineType.VOSK -> "Vosk"; else -> "translation" }
    fun import(uri: android.net.Uri, folder: Boolean) { scope.launch {
        busy = true; message = "Copying and verifying $name… Keep this screen open."
        try {
            val model = (if (folder) registry.importModelDirectory(uri) else registry.importModel(uri))
                ?: error("Unsupported model structure. Check the installation steps below.")
            check(model.engineType == type && model.isValid) { "Imported ${model.engineType.displayName}; choose it in its matching model group." }
            if (type != ModelEngineType.TRANSLATE) update { it.copy(engine = if (type == ModelEngineType.WHISPER) CaptionEngineChoice.WHISPER else CaptionEngineChoice.VOSK,
                modelId = model.id, mode = CaptionMode.CAPTIONS, streamLanguage = "auto") }
            message = "${model.name} imported${if (type != ModelEngineType.TRANSLATE) " and selected for CC" else ""}."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: e.toString() }
        finally { busy = false }
    } }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { import(it, false) } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { import(it, true) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val installed = models.filter { it.engineType == type }
        Text("Installed ${name} models", style = MaterialTheme.typography.titleSmall)
        if (installed.isEmpty()) Text("None installed yet.", style = MaterialTheme.typography.bodySmall)
        installed.forEach { model ->
            if (type == ModelEngineType.TRANSLATE) Text("${model.name} · ${if (model.isValid) "Verified" else "Needs attention"}")
            else FilterChip(selected = config.modelId == model.id, enabled = !busy && model.isValid,
                onClick = { update { it.copy(engine = if (type == ModelEngineType.WHISPER) CaptionEngineChoice.WHISPER else CaptionEngineChoice.VOSK, modelId = model.id) } },
                label = { Text("${model.name} · ${model.sizeBytes / 1_048_576} MiB${if (!model.isValid) " · Needs attention" else ""}") })
        }
        OutlinedButton(enabled = !busy, onClick = { if (type == ModelEngineType.WHISPER) file.launch(arrayOf("*/*")) else folder.launch(null) }) {
            Text("Import $name ${if (type == ModelEngineType.WHISPER) "file" else "folder"}")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
        ModelSourcePanel(if (type == ModelEngineType.TRANSLATE) listOf(ModelSources.marian) else ModelSources.speech(if (type == ModelEngineType.WHISPER) CaptionEngineChoice.WHISPER else CaptionEngineChoice.VOSK))
    }
}
