package com.sal7one.transiber.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionMode
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.models.SpeechArtifactKind
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.ui.components.ModelFilterPicker
import kotlinx.coroutines.*
import java.io.File

/** Goal-first setup uses the same pinned model resolver as portable bundles. */
@Composable
internal fun LocalBundleUi(
    config: CaptionOverlayConfig,
    onDone: () -> Unit = {},
    onDownloads: () -> Unit = {},
    translationOnly: Boolean = false,
) {
    val text = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { PortableSetupActions(context) }
    val registry = remember { ModelRegistry.getInstance(context) }
    var source by rememberSaveable { mutableStateOf(config.streamLanguage.takeIf { it in LanguageCatalog.whisperCodes } ?: "en") }
    var target by rememberSaveable { mutableStateOf(config.target.languageTag) }
    var kind by rememberSaveable { mutableStateOf(if (translationOnly || config.mode == CaptionMode.TRANSLATE)
        LocalBundleKind.FAST_TRANSLATION else LocalBundleKind.COMPACT_CAPTIONS) }
    var preview by remember { mutableStateOf<PortableSetupPreview?>(null) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var inspectedInstallState by remember(kind, source, target, translationOnly) { mutableStateOf<String?>(null) }
    val bundle = remember(kind, source, target, translationOnly) { LocalBundlePresets.create(kind, source, target, translationOnly) }
    suspend fun refresh(force: Boolean = false) {
        records = withContext(Dispatchers.IO) { if (ByokPolicy.FEATURE_BYOK) FileDownloads(context).list() else emptyList() }
        // Resolving a portable recipe hashes installed weights. Do that on
        // selection/import/install changes, never on every transfer-byte tick.
        val installedState = records.filter { it.installed }.map { "${it.id}:${it.installedId}" }.sorted().joinToString("|")
        if (force || preview == null || installedState != inspectedInstallState) {
            preview = bundle?.let { actions.preview(it.setup) }
            inspectedInstallState = installedState
        }
    }
    LaunchedEffect(bundle) {
        preview = null
        while (isActive) {
            try { refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            delay(1500)
        }
    }
    LaunchedEffect(requested, preview?.ready, bundle) {
        if (requested && preview?.ready == true && bundle != null) {
            busy = true
            try { actions.apply(bundle.setup); requested = false; message = text(R.string.setup_bundle_complete); onDone() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { requested = false; error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    val relatedIds = remember(bundle) {
        bundle?.let { (if (translationOnly) emptySet() else setOf(it.speech.id)) + listOfNotNull(it.translatorId) +
            (it.translatorId?.let(MarianPackage::find)?.parts?.map { part -> part.id } ?: emptyList()) } ?: emptySet()
    }
    val related = records.filter { it.modelId in relatedIds || it.title == bundle?.speech?.fileName }
    LaunchedEffect(requested, related) {
        if (requested) related.firstOrNull { failed -> failed.failed && related.none { other ->
            !other.failed && (if (failed.modelId.isNotBlank()) other.modelId == failed.modelId else other.title == failed.title)
        } }?.let {
            requested = false; error = it.error.ifBlank { it.phase }
        }
    }
    var importSpeech by rememberSaveable { mutableStateOf(true) }
    fun import(uri: android.net.Uri, directory: Boolean) {
        val selected = bundle ?: return
        scope.launch {
            busy = true; error = null; message = null
            try {
                withContext(Dispatchers.IO) {
                    if (directory) {
                        val model = registry.importModelDirectory(uri, checkNotNull(MarianPackage.find(checkNotNull(selected.translatorId))).treeSha256) ?: error("Unsupported translation folder")
                        check(model.isValid && model.engineType == ModelEngineType.TRANSLATE) { "Choose a translation model folder" }
                    } else if (importSpeech && selected.speech.kind == SpeechArtifactKind.WHISPER) {
                        val model = registry.importModel(uri, selected.speech.sha256) ?: error("Unsupported Whisper model")
                        check(model.isValid && model.engineType == ModelEngineType.WHISPER) { "Choose the Whisper file shown in this bundle" }
                    } else {
                        val job = currentCoroutineContext()
                        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                            if (importSpeech) {
                                val store = LocalSpeechModels(File(context.filesDir, "speech-models"))
                                val publisher = checkNotNull(selected.speech.publisher)
                                input.mark(4); val magic = ByteArray(4); input.read(magic); input.reset()
                                val model = if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())
                                    store.importZip(input) { job.ensureActive() }
                                else store.installPublisher(input, publisher, -System.nanoTime()) { job.ensureActive() }
                                check(model.profile == publisher.profile) { "Choose the speech model shown in this bundle" }
                                ModelSourceBindings.recordImported(context, model.id, uri, listOf(model.root))
                            } else {
                                val spec = TranslationCatalog.find(checkNotNull(selected.translatorId))
                                val file = LocalTranslationModels(File(context.filesDir, "translation-models"))
                                    .import(input, spec) { job.ensureActive() }
                                ModelSourceBindings.recordImported(context, spec.id, uri, listOf(file))
                            }
                        } ?: error("Cannot open the model file")
                    }
                }
                refresh(force = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> import(uri, false) } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { uri -> import(uri, true) } }
    val sourceOptions = remember(translationOnly) { (if (translationOnly) TranslationCatalog.find(LocalBundlePresets.BROAD_TRANSLATOR).sourceLanguages else LanguageCatalog.whisperCodes).map { it to TranslationLanguages.label(it) }.sortedBy { it.second } }
    val targetOptions = remember { TranslationCatalog.find(LocalBundlePresets.BROAD_TRANSLATOR).targetLanguages
        .map { it to TranslationLanguages.label(it) }.sortedBy { it.second } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text(R.string.setup_models_title), style = MaterialTheme.typography.headlineSmall)
        Text(text(R.string.setup_models_detail), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelFilterPicker(text(R.string.setup_bundle_source), source, sourceOptions,
                { source = it; message = null; error = null }, Modifier.weight(1f), enabled = !busy && !requested)
            ModelFilterPicker(text(R.string.setup_bundle_target), target, targetOptions,
                { target = it; message = null; error = null }, Modifier.weight(1f), enabled = !busy && !requested)
        }
        LocalBundleKind.entries.filter { !translationOnly || it != LocalBundleKind.COMPACT_CAPTIONS }.forEach { choice ->
            val item = LocalBundlePresets.create(choice, source, target, translationOnly)
            Card(onClick = { kind = choice; error = null; message = null }, enabled = !busy && !requested && item != null,
                modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
                    if (kind == choice) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioButton(kind == choice, onClick = null)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text(when (choice) {
                            LocalBundleKind.COMPACT_CAPTIONS -> R.string.setup_bundle_compact
                            LocalBundleKind.FAST_TRANSLATION -> R.string.setup_bundle_fast
                            LocalBundleKind.BROAD_TRANSLATION -> R.string.setup_bundle_broad
                        }), style = MaterialTheme.typography.titleMedium)
                        Text(text(if (translationOnly) when (choice) {
                            LocalBundleKind.FAST_TRANSLATION -> R.string.setup_bundle_compact_translation_only
                            else -> R.string.setup_bundle_broad_translation_only
                        } else when (choice) {
                            LocalBundleKind.COMPACT_CAPTIONS -> R.string.setup_bundle_compact_detail
                            LocalBundleKind.FAST_TRANSLATION -> R.string.setup_bundle_fast_detail
                            LocalBundleKind.BROAD_TRANSLATION -> R.string.setup_bundle_broad_detail
                        }), style = MaterialTheme.typography.bodySmall)
                        Text(item?.let { text(R.string.setup_bundle_total, (it.downloadBytes + 1_048_575) / 1_048_576) }
                            ?: text(R.string.setup_bundle_unavailable), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text(text(R.string.setup_bundle_device_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            bundle == null -> Text(text(R.string.setup_bundle_unavailable), color = MaterialTheme.colorScheme.error)
            preview?.ready == true -> Text(text(R.string.setup_bundle_ready), color = MaterialTheme.colorScheme.primary)
            requested -> Text(text(R.string.setup_bundle_pending))
            preview == null -> Text(text(R.string.setup_bundle_preparing))
            else -> Text(text(R.string.setup_bundle_missing), style = MaterialTheme.typography.bodySmall)
        }
        preview?.issues?.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy || requested || (bundle != null && preview == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ByokPolicy.FEATURE_BYOK || preview?.ready == true) Button(
            enabled = !busy && !requested && bundle != null && preview != null && preview?.issues?.isEmpty() == true,
            onClick = { scope.launch {
                val selected = bundle ?: return@launch
                busy = true; error = null; message = null
                try {
                    if (preview?.ready == true) { actions.apply(selected.setup); message = text(R.string.setup_bundle_complete); onDone() }
                    else { actions.downloadMissing(selected.setup); requested = true; refresh() }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: e.toString() }
                finally { busy = false }
            } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(text(if (preview?.ready == true) R.string.setup_bundle_use else R.string.setup_bundle_download))
        }
        if (requested) TextButton(onClick = { requested = false }) { Text(text(R.string.setup_bundle_finish_later)) }
        if (ByokPolicy.FEATURE_BYOK && related.isNotEmpty()) TextButton(onClick = onDownloads) { Text(text(R.string.setup_bundle_downloads)) }
        OutlinedButton(onClick = { importing = !importing }, enabled = !busy && !requested && bundle != null,
            modifier = Modifier.fillMaxWidth()) { Text(text(R.string.setup_bundle_import)) }
        if (importing) {
            if (!translationOnly) {
                Text(bundle?.speech?.fileName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { importSpeech = true; filePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(text(R.string.setup_bundle_import_speech)) }
            }
            if (bundle?.translatorId != null) {
                Text(bundle.translatorId, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    importSpeech = false
                    if (MarianPackage.find(bundle.translatorId) != null) folderPicker.launch(null) else filePicker.launch(arrayOf("*/*"))
                }, enabled = !busy) { Text(text(R.string.setup_bundle_import_translation)) }
            }
        }
        TextButton(onClick = { details = !details }) { Text(text(R.string.setup_bundle_details)) }
        if (details) {
            bundle?.let {
                if (!translationOnly) {
                    Text(it.speech.label, style = MaterialTheme.typography.bodySmall)
                    Text(it.speech.license, style = MaterialTheme.typography.bodySmall)
                }
                it.translatorId?.let { id -> Text(com.sal7one.transiber.translation.TranslationOptions.label(id), style = MaterialTheme.typography.bodySmall) }
            }
            preview?.issues?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            related.forEach { Text("${it.title} · ${it.phase} · ${it.bytes / 1_048_576} MiB", style = MaterialTheme.typography.bodySmall) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
