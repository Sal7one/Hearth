package com.sal7one.transiber.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.hearth.core.models.ModelPerformanceCatalog
import com.sal7one.hearth.core.models.PerformancePlatform
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionMode
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.caption.rememberCaptionSetupResumeRevision
import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.models.SpeechArtifactKind
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.ui.components.ModelFilterPicker
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** Goal-first setup uses the same pinned model resolver as portable bundles. */
@Composable
internal fun LocalBundleUi(
    config: CaptionOverlayConfig,
    onDone: () -> Unit = {},
    onDownloads: (() -> Unit)? = null,
    translationOnly: Boolean = false,
    showHeading: Boolean = true,
    onBrowseModels: ((Boolean) -> Unit)? = null,
) {
    val text = rememberUiText()
    val context = LocalContext.current
    val links = LocalUriHandler.current
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) {
        com.sal7one.transiber.ui.components.LocalModelPolicyNotice()
        return
    }
    val scope = rememberCoroutineScope()
    val actions = remember { PortableSetupActions(context) }
    val registry = remember { ModelRegistry.getInstance(context) }
    val downloads = remember { if (ByokPolicy.FEATURE_BYOK) FileDownloads(context) else null }
    val supportsCt2 = remember { Ct2Package.runtimeSupported() }
    var translates by rememberSaveable { mutableStateOf(translationOnly || config.mode == CaptionMode.TRANSLATE) }
    var source by rememberSaveable {
        val saved = config.streamLanguage.takeIf { it in LanguageCatalog.whisperCodes }
        val device = Locale.getDefault().language.takeIf { it in LanguageCatalog.whisperCodes }
        mutableStateOf(saved ?: device ?: "en")
    }
    var target by rememberSaveable {
        mutableStateOf(config.target.languageTag.takeUnless { it == source } ?: if (source == "en") "ar" else "en")
    }
    var preferredCaptions by rememberSaveable { mutableStateOf<LocalBundleKind?>(null) }
    var preferredTranslation by rememberSaveable { mutableStateOf<LocalBundleKind?>(null) }
    val choices = remember(translates, source, target, translationOnly, supportsCt2) {
        LocalBundleKind.entries.filter { it.translates == translates }.mapNotNull {
            LocalBundlePresets.create(it, source, target, translationOnly, supportsCt2)
        }
    }
    val preferred = if (translates) preferredTranslation else preferredCaptions
    val bundle = choices.firstOrNull { it.kind == preferred } ?: choices.firstOrNull()
    var verified by remember { mutableStateOf<Pair<LocalBundlePreset, PortableSetupPreview>?>(null) }
    val preview = verified?.takeIf { it.first == bundle }?.second
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    var details by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val resumed = rememberCaptionSetupResumeRevision()
    val relatedIds = remember(bundle) {
        buildSet {
            bundle?.speech?.id?.let(::add)
            bundle?.translatorId?.let { id ->
                add(id)
                MarianPackage.find(id)?.parts?.forEach { add(it.id) }
                Ct2Catalog.find(id)?.let { spec -> Ct2Package.parts(spec).forEach { add(it.id) } }
            }
        }
    }
    val related = records.filter { it.modelId in relatedIds || (it.modelId.isEmpty() && it.title == bundle?.speech?.fileName) }
    val active = preview?.ready != true && related.any { it.active || it.preparingQueued }
    val paused = preview?.ready != true && !active && related.any { it.paused }
    val failed = related.firstOrNull { failed -> failed.failed && related.none { other ->
        !other.failed && (if (failed.modelId.isNotBlank()) other.modelId == failed.modelId else other.title == failed.title)
    } }

    LaunchedEffect(bundle, resumed, revision) {
        val selected = bundle ?: return@LaunchedEffect
        var inspectedState: List<Pair<Long, String>>? = null
        do {
            try {
                records = withContext(Dispatchers.IO) { downloads?.list().orEmpty() }
                // Checks on selection, resume or a completed install, never on each byte tick.
                val installedState = records.filter { it.installed }.map { it.id to it.installedId }
                if (installedState != inspectedState) {
                    verified = selected to actions.preview(selected.setup)
                    inspectedState = installedState
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            if (downloads != null) delay(1500)
        } while (downloads != null && isActive)
    }

    var importSpeech by rememberSaveable { mutableStateOf(true) }
    fun import(uri: Uri, directory: Boolean) {
        val selected = bundle ?: return
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                withContext(Dispatchers.IO) {
                    val speech = selected.speech.takeIf { importSpeech }
                    val translation = selected.translatorId.takeUnless { importSpeech }
                    if (directory) {
                        val pair = checkNotNull(translation?.let(MarianPackage::find))
                        val model = registry.importModelDirectory(uri, pair.treeSha256) ?: error("Unsupported translation folder")
                        check(model.isValid && model.engineType == ModelEngineType.TRANSLATE) { "Choose a translation model folder" }
                    } else if (speech?.kind == SpeechArtifactKind.WHISPER) {
                        val model = registry.importModel(uri, speech.sha256) ?: error("Unsupported Whisper model")
                        check(model.isValid && model.engineType == ModelEngineType.WHISPER) { "Choose the Whisper file shown in this bundle" }
                    } else {
                        val job = currentCoroutineContext()
                        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                            if (speech != null) {
                                val store = LocalSpeechModels(File(context.filesDir, "speech-models"))
                                val publisher = checkNotNull(speech.publisher)
                                input.mark(4)
                                val magic = ByteArray(4)
                                input.read(magic)
                                input.reset()
                                val model = if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())
                                    store.importZip(input) { job.ensureActive() }
                                else store.installPublisher(input, publisher, -System.nanoTime()) { job.ensureActive() }
                                check(model.profile == publisher.profile) { "Choose the speech model shown in this bundle" }
                                ModelSourceBindings.recordImported(context, model.id, uri, listOf(model.root))
                            } else {
                                val id = checkNotNull(translation)
                                val ct2 = Ct2Catalog.find(id)
                                val file = if (ct2 != null) Ct2Package.importZip(context, input, listOf(ct2)) { job.ensureActive() }.second
                                else LocalTranslationModels(File(context.filesDir, "translation-models"))
                                    .import(input, TranslationCatalog.find(id)) { job.ensureActive() }
                                ModelSourceBindings.recordImported(context, id, uri, listOf(file))
                            }
                        } ?: error("Cannot open the model file")
                    }
                }
                revision++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> import(uri, false) } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { uri -> import(uri, true) } }
    val sourceOptions = remember(translationOnly) {
        val codes = if (translationOnly) TranslationCatalog.find(LocalBundlePresets.BROAD_TRANSLATOR).sourceLanguages else LanguageCatalog.whisperCodes
        codes.map { it to TranslationLanguages.label(it) }.sortedBy { it.second }
    }
    val targetOptions = remember {
        val codes = TranslationCatalog.find(LocalBundlePresets.BROAD_TRANSLATOR).targetLanguages +
            Ct2Catalog.models.flatMap { it.targets } + MarianPackage.pairs.map { it.target }
        codes.map { it to TranslationLanguages.label(it) }.sortedBy { it.second }
    }
    fun clearFeedback() {
        error = null
        message = null
        importing = false
    }
    val missingBytes = preview?.let {
        (bundle?.speech?.bytes?.takeIf { _ -> it.missingSpeechCatalogId != null } ?: 0) +
            it.missingTranslationIds.sumOf(LocalBundlePresets::translationBytes)
    }
    val current = bundle?.let { preview?.ready == true && it.matchesCurrent(config, preview) } ?: false

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) {
            Text(text(R.string.setup_models_title), style = MaterialTheme.typography.headlineSmall)
            Text(text(R.string.setup_models_detail), style = MaterialTheme.typography.bodyMedium)
        }
        if (!translationOnly) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !translates, onClick = { translates = false; clearFeedback() },
                enabled = !busy, label = { Text(text(R.string.setup_preset_goal_captions)) })
            FilterChip(selected = translates, onClick = { translates = true; clearFeedback() },
                enabled = !busy, label = { Text(text(R.string.setup_preset_goal_translation)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelFilterPicker(text(if (translationOnly) R.string.setup_preset_source_text else R.string.setup_bundle_source), source, sourceOptions,
                { source = it; clearFeedback() }, Modifier.weight(1f), enabled = !busy)
            if (translates) ModelFilterPicker(text(R.string.setup_bundle_target), target, targetOptions,
                { target = it; clearFeedback() }, Modifier.weight(1f), enabled = !busy)
        }
        choices.forEach { choice ->
            val selected = choice == bundle
            val models = listOfNotNull(choice.speech?.label, choice.translatorId?.let(TranslationOptions::label))
            val speechMeasurement = choice.speech?.id?.let { ModelPerformanceCatalog.forModel(it, PerformancePlatform.ANDROID) }
                ?.firstOrNull { it.language == source }
            val translationMeasurement = choice.translatorId?.let { ModelPerformanceCatalog.forModel(it, PerformancePlatform.ANDROID) }
                ?.firstOrNull { it.language == "$source→$target" }
            val measurements = listOfNotNull(speechMeasurement, translationMeasurement)
            Card(onClick = {
                if (translates) preferredTranslation = choice.kind else preferredCaptions = choice.kind
                clearFeedback()
            }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioButton(selected, onClick = null)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text(choice.kind.title), style = MaterialTheme.typography.titleMedium)
                        Text(text(choice.kind.detail(translationOnly)), style = MaterialTheme.typography.bodySmall)
                        Text(models.joinToString(" + "), style = MaterialTheme.typography.bodyMedium)
                        Text(text(R.string.setup_bundle_total, mib(choice.downloadBytes)), style = MaterialTheme.typography.labelMedium)
                        if (selected && current) Text(text(R.string.setup_preset_current), style = MaterialTheme.typography.labelMedium)
                        measurements.forEach { measurement ->
                            Text(measurement.label, style = MaterialTheme.typography.labelLarge)
                            Text(measurement.context, style = MaterialTheme.typography.bodySmall)
                            if (selected && ByokPolicy.FEATURE_BYOK) TextButton(onClick = {
                                runCatching { links.openUri(measurement.sourceUrl) }
                                    .onFailure { error = it.message ?: it.toString() }
                            }) { Text(text(R.string.setup_preset_measurement_source)) }
                        }
                    }
                }
            }
        }
        when {
            bundle == null -> Text(text(R.string.setup_preset_pair_unavailable), color = MaterialTheme.colorScheme.error)
            busy -> Text(text(R.string.setup_preset_working))
            preview == null -> Text(text(R.string.setup_bundle_preparing))
            preview.ready -> Text(text(R.string.setup_bundle_ready), color = MaterialTheme.colorScheme.primary)
            active -> Text(text(R.string.setup_bundle_pending))
            paused -> Text(text(R.string.setup_preset_queue_paused))
            missingBytes != null && missingBytes > 0 ->
                Text(text(R.string.setup_preset_remaining, mib(missingBytes)), style = MaterialTheme.typography.bodyMedium)
        }
        preview?.issues?.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (preview?.ready != true && !active) failed?.let {
            Text(it.error.ifBlank { it.phase }, color = MaterialTheme.colorScheme.error)
        }
        if (busy || active || (bundle != null && preview == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (bundle != null && (ByokPolicy.FEATURE_BYOK || preview?.ready == true)) Button(
            enabled = !busy && !active && !paused && preview?.issues?.isEmpty() == true,
            onClick = { scope.launch {
                busy = true
                error = null
                message = null
                try {
                    if (preview?.ready == true) {
                        actions.apply(bundle.setup)
                        message = text(R.string.setup_preset_applied)
                        onDone()
                    } else {
                        actions.downloadMissing(bundle.setup)
                        revision++
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: e.toString() }
                finally { busy = false }
            } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(text(if (preview?.ready == true) R.string.setup_bundle_use else R.string.setup_preset_download_missing))
        }
        if (onDownloads != null && ByokPolicy.FEATURE_BYOK && related.isNotEmpty()) TextButton(onClick = onDownloads) {
            Text(text(R.string.setup_bundle_downloads))
        }
        if (bundle != null) {
            OutlinedButton(onClick = { importing = !importing }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text(text(R.string.setup_bundle_import)) }
            if (importing) {
                bundle.speech?.let {
                    Text(it.fileName, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { importSpeech = true; filePicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                        Text(text(R.string.setup_bundle_import_speech))
                    }
                }
                bundle.translatorId?.let { id ->
                    Text(TranslationOptions.label(id), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        importSpeech = false
                        if (MarianPackage.find(id) != null) folderPicker.launch(null) else filePicker.launch(arrayOf("*/*"))
                    }, enabled = !busy) { Text(text(R.string.setup_bundle_import_translation)) }
                }
            }
            TextButton(onClick = { details = !details }) { Text(text(R.string.setup_bundle_details)) }
            if (details) {
                bundle.speech?.license?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                preview?.issues?.drop(1)?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                related.forEach { Text("${it.title} · ${it.phase} · ${it.bytes / 1_048_576} MiB", style = MaterialTheme.typography.bodySmall) }
            }
        }
        onBrowseModels?.let { browse ->
            TextButton(onClick = { browse(translates) }) { Text(text(R.string.setup_preset_browse_models)) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun mib(bytes: Long) = (bytes + 1_048_575) / 1_048_576

private fun LocalBundlePreset.matchesCurrent(config: CaptionOverlayConfig, preview: PortableSetupPreview): Boolean {
    if (speech == null || config.engine != preview.speechEngine || config.modelId != preview.speechModelId ||
        config.streamLanguage != setup.speech?.sourceLanguage || (config.mode == CaptionMode.TRANSLATE) != kind.translates) return false
    val translation = setup.translation ?: return !config.localTranslationEnabled
    return config.localTranslationEnabled && config.textTranslationProviderId == "local" &&
        config.localTranslationModelId == translation.modelId && config.target.languageTag == translation.targetLanguage
}

private val LocalBundleKind.title get() = when (this) {
    LocalBundleKind.SHORT_CAPTIONS -> R.string.setup_preset_short_captions
    LocalBundleKind.PHONON_CAPTIONS -> R.string.setup_preset_phonon
    LocalBundleKind.STREAMING_CAPTIONS -> R.string.setup_preset_streaming
    LocalBundleKind.COMPACT_CAPTIONS -> R.string.setup_bundle_compact
    LocalBundleKind.FAST_TRANSLATION -> R.string.setup_bundle_fast
    LocalBundleKind.QUICKMT_TRANSLATION -> R.string.setup_preset_quickmt
    LocalBundleKind.BROAD_TRANSLATION -> R.string.setup_bundle_broad
}

private fun LocalBundleKind.detail(translationOnly: Boolean) = when (this) {
    LocalBundleKind.SHORT_CAPTIONS, LocalBundleKind.PHONON_CAPTIONS -> R.string.setup_preset_short_detail
    LocalBundleKind.STREAMING_CAPTIONS -> R.string.setup_preset_streaming_detail
    LocalBundleKind.COMPACT_CAPTIONS -> R.string.setup_bundle_compact_detail
    LocalBundleKind.FAST_TRANSLATION -> R.string.setup_preset_marian_detail
    LocalBundleKind.QUICKMT_TRANSLATION -> R.string.setup_preset_quickmt_detail
    LocalBundleKind.BROAD_TRANSLATION ->
        if (translationOnly) R.string.setup_bundle_broad_translation_only else R.string.setup_bundle_broad_detail
}
