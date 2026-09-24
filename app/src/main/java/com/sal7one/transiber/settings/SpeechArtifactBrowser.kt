package com.sal7one.transiber.settings

import android.app.DownloadManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** A small family → checkpoint → quant browser; only pinned, installable artifacts are shown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeechArtifactBrowser(config: CaptionOverlayConfig) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val downloads = remember { FileDownloads(context.applicationContext) }
    var records by remember { mutableStateOf(emptyList<FileDownload>()) }
    var importedWhisper by remember { mutableStateOf(emptyList<ModelRegistry.RegisteredModel>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val families = remember { SpeechArtifactCatalog.all.groupBy { it.family }.toSortedMap() }
    val suggestedFamily = when (config.engine) {
        CaptionEngineChoice.MOONSHINE -> "moonshine"
        CaptionEngineChoice.QWEN -> "qwen3-asr"
        CaptionEngineChoice.OMNILINGUAL -> "omnilingual"
        CaptionEngineChoice.NEMOTRON -> "nemotron"
        else -> "whisper"
    }
    var family by rememberSaveable { mutableStateOf(suggestedFamily) }
    val familyArtifacts = families[family].orEmpty()
    var checkpoint by rememberSaveable(family) { mutableStateOf(familyArtifacts.firstOrNull()?.checkpoint.orEmpty()) }
    val checkpointArtifacts = familyArtifacts.filter { it.checkpoint == checkpoint }
    var variant by rememberSaveable(family, checkpoint) { mutableStateOf(
        checkpointArtifacts.firstOrNull { it.id == config.modelId }?.id ?: checkpointArtifacts.firstOrNull()?.id.orEmpty()
    ) }
    val artifact = SpeechArtifactCatalog.find(variant)

    LaunchedEffect(Unit) {
        if (ByokPolicy.FEATURE_BYOK) while (isActive) {
            try {
                records = withContext(Dispatchers.IO) { downloads.list() }
                importedWhisper = ModelRegistry.getInstance(context).registeredModels.value
                    .filter { it.engineType == ModelEngineType.WHISPER && it.isValid }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val record = records.firstOrNull { it.modelId == artifact?.id }
    val importedArtifact = artifact?.takeIf { it.kind == SpeechArtifactKind.WHISPER }?.let { selected ->
        importedWhisper.firstOrNull { it.digest?.hex.equals(selected.sha256, ignoreCase = true) }
    }
    val alreadyImported = importedArtifact != null

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(uiText(UiR.string.speech_artifacts_heading), style = MaterialTheme.typography.titleMedium)
            Text(uiText(UiR.string.speech_artifacts_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                families.keys.forEach { id ->
                    val title = when (id) {
                        "nemotron" -> "Nemotron"
                        "qwen3-asr" -> "Qwen3-ASR"
                        "omnilingual" -> "Omnilingual"
                        "whisper" -> "Whisper"
                        else -> "Moonshine"
                    }
                    FilterChip(selected = family == id, onClick = { family = id }, enabled = !busy, label = { Text(title) })
                }
            }
            if (familyArtifacts.map { it.checkpoint }.distinct().size > 1) {
                Text(uiText(UiR.string.speech_model_size), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    familyArtifacts.distinctBy { it.checkpoint }.forEach { item ->
                        FilterChip(selected = checkpoint == item.checkpoint, onClick = { checkpoint = item.checkpoint }, enabled = !busy,
                            label = { Text(item.checkpoint.replace(".en", " · English")) })
                    }
                }
            }
            Text(uiText(UiR.string.speech_quantization), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                checkpointArtifacts.forEach { item ->
                    FilterChip(selected = variant == item.id, onClick = { variant = item.id }, enabled = !busy,
                        label = { Text("${item.quant} · ${item.bytes / 1_048_576} MiB") })
                }
            }
            artifact?.let { item ->
                Text(item.label, style = MaterialTheme.typography.titleSmall)
                Text(uiText(UiR.string.speech_artifact_languages, item.languages.sorted().joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall)
                Text(uiText(UiR.string.speech_artifact_license, item.license), style = MaterialTheme.typography.bodySmall)
                val reserveNeed = DownloadBudget.estimate(item.bytes, item.installedBytes).sameVolumeRequired
                Text(uiText(UiR.string.speech_artifact_space, reserveNeed.toMiBString(), downloads.locationLabel),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!ByokPolicy.FEATURE_BYOK) {
                    Text(uiText(UiR.string.speech_artifact_import_play_only), style = MaterialTheme.typography.bodySmall)
                } else {
                    val active = record?.active == true
                    val installed = record?.installed == true || alreadyImported
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && !active, onClick = {
                        scope.launch {
                            busy = true; error = null; message = null
                            try {
                                if (record?.installed == true) downloads.selectInstalled(record)
                                else if (importedArtifact != null) {
                                    CaptionConfigStore.update(context) { it.copy(engine = CaptionEngineChoice.WHISPER, modelId = importedArtifact.id, streamLanguage = "auto") }
                                }
                                else if (record?.paused == true) downloads.retry(record.id)
                                else if (record?.failed == true && record.id < 0) downloads.retry(record.id)
                                else withContext(Dispatchers.IO) {
                                    downloads.enqueue(DownloadSpec.parse(item.url, item.fileName), modelPackage = true, installModelId = item.id)
                                }
                                records = withContext(Dispatchers.IO) { downloads.list() }
                                message = if (installed) uiText(UiR.string.speech_artifact_selected) else null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                            finally { busy = false }
                        }
                    }) {
                        Text(when {
                            active -> uiText(UiR.string.ui_downloading_1_s_mib_e010b, record.bytes / 1_048_576)
                            installed -> uiText(UiR.string.speech_artifact_select)
                            record?.paused == true -> uiText(UiR.string.ui_resume_download)
                            record?.failed == true -> uiText(UiR.string.ui_retry_download_install_e5444)
                            else -> uiText(UiR.string.speech_artifact_download, (item.bytes / 1_048_576).toString())
                        })
                    }
                    if (active && (record?.total ?: -1L) > 0) LinearProgressIndicator(
                        progress = { (record!!.bytes.toFloat() / record.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    record?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { uriHandler.openUri(item.publisherUrl) }) { Text(uiText(UiR.string.ui_source_license_397e9)) }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun Long.toMiBString(): String = String.format(Locale.getDefault(), "%,d", this / 1_048_576)
