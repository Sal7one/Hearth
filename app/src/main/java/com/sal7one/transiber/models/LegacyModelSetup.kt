package com.sal7one.transiber.models

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit, showDownloadSources: Boolean = true) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    val registry = remember { ModelRegistry.getInstance(context) }
    val models by registry.registeredModels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val importOwner = "legacy-${type.name}"
    val importBatches by ModelImportQueue.state.collectAsState()
    val busy = importBatches[importOwner]?.active == true
    var message by remember(type) { mutableStateOf<String?>(null) }
    val name = when (type) { ModelEngineType.WHISPER -> "Whisper"; ModelEngineType.VOSK -> "Vosk"; else -> uiText(UiR.string.ui_translation_ac26a) }
    fun importFiles(uris: List<android.net.Uri>, folder: Boolean) {
        if (uris.isEmpty()) return
        try {
            ModelImportQueue.submit(context.applicationContext, importOwner, uris) { source ->
                val model = (if (folder) registry.importModelDirectory(source.uri) else registry.importModel(source.uri))
                    ?: error("Unsupported model structure. Choose a supported model file or folder.")
                check(model.engineType == type && model.isValid) { "Imported ${model.engineType.displayName}; choose it in its matching model group." }
                model.name
            }
        } catch (e: Exception) { message = e.message ?: e.toString() }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importFiles(it, false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { importFiles(listOf(it), true) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val installed = models.filter { it.engineType == type }
        Text(uiText(UiR.string.ui_installed_1_s_models_4b3c9, name), style = MaterialTheme.typography.titleSmall)
        if (installed.isEmpty()) Text(uiText(UiR.string.ui_none_installed_yet_50d82), style = MaterialTheme.typography.bodySmall)
        installed.forEach { model ->
            if (type == ModelEngineType.TRANSLATE) Text("${model.name} · ${if (model.isValid) uiText(UiR.string.verified) else uiText(UiR.string.needs_attention)}")
            else FilterChip(selected = config.modelId == model.id, enabled = !busy && model.isValid,
                onClick = { update { it.copy(engine = if (type == ModelEngineType.WHISPER) CaptionEngineChoice.WHISPER else CaptionEngineChoice.VOSK, modelId = model.id) } },
                label = { Text(uiText(UiR.string.ui_1_s_2_s_mib_3_s_fbd52, model.name, model.sizeBytes / 1_048_576, if (!model.isValid) uiText(UiR.string.needs_attention_suffix) else "")) })
        }
        OutlinedButton(enabled = !busy, onClick = { if (type == ModelEngineType.WHISPER) file.launch(arrayOf("*/*")) else folder.launch(null) }) {
            Text(uiText(UiR.string.ui_import_1_s_2_s_5dda0, name, if (type == ModelEngineType.WHISPER) uiText(UiR.string.import_file) else uiText(UiR.string.import_folder)))
        }
        ModelImportStatus(importOwner)
        message?.let { Text(it) }
        if (showDownloadSources) ModelSourcePanel(if (type == ModelEngineType.TRANSLATE) listOf(ModelSources.marian) else ModelSources.speech(if (type == ModelEngineType.WHISPER) CaptionEngineChoice.WHISPER else CaptionEngineChoice.VOSK))
    }
}
