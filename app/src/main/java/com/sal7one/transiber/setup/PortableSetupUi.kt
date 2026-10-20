package com.sal7one.transiber.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.ui.components.ModelFilterPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Import → preview → apply. Download completion never applies a setup in the background. */
@Composable
internal fun PortableSetupUi(onSpeechModels: () -> Unit, onTranslationModels: () -> Unit,
    onVoices: () -> Unit, onDownloads: () -> Unit) {
    val context = LocalContext.current; val text = rememberUiText(); val scope = rememberCoroutineScope()
    val actions = remember(context) { PortableSetupActions(context) }
    var setup by remember { mutableStateOf<PortableSetup?>(null) }
    var preview by remember { mutableStateOf<PortableSetupPreview?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var includeSpeech by remember { mutableStateOf(true) }
    var includeTranslation by remember { mutableStateOf(true) }
    var includeVoice by remember { mutableStateOf(true) }
    var exportScope by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    fun job(block: suspend () -> Unit) { scope.launch {
        busy = true; error = null; notice = null
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
    } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job {
            val imported = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use(PortableSetup::read)
                ?: error("Cannot open the selected setup file") }
            preview = null; setup = imported; revision++
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val raw = pendingJson; pendingJson = null
        if (uri != null && raw != null) job {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
                ?: error("Cannot write the selected setup file") }
            notice = text(R.string.portable_setup_saved)
        }
    }
    LaunchedEffect(setup?.speech, setup?.translation, setup?.voice, revision) {
        preview = null
        setup?.let { current ->
            try { preview = actions.preview(current) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text(R.string.portable_setup_title), style = MaterialTheme.typography.titleMedium)
            Text(text(R.string.portable_setup_description), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy,
                    modifier = Modifier.weight(1f)) { Text(text(R.string.portable_setup_import)) }
                OutlinedButton(onClick = { exportScope = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(text(R.string.portable_setup_current)) }
            }
            setup?.let { current ->
                HorizontalDivider()
                OutlinedTextField(current.name, { value -> if (value.isNotBlank() && value.length <= 80 && value.none { it.isISOControl() }) setup = current.copy(name = value) },
                    label = { Text(text(R.string.portable_setup_name)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                current.speech?.let { stage ->
                    val artifact = stage.model.catalogId?.let(SpeechArtifactCatalog::find)
                    Text(text(R.string.portable_setup_speech) + ": " + (artifact?.label ?: stage.model.profile ?: stage.model.family), style = MaterialTheme.typography.bodyMedium)
                    if (stage.model.sha256 != null) {
                        TextButton(onClick = { details = !details }) { Text(text(R.string.portable_setup_details)) }
                        if (details) Text("SHA-256: ${stage.model.sha256}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                current.translation?.let { stage -> Text(text(R.string.portable_setup_translation) + ": " +
                    (if (stage.enabled) TranslationOptions.label(stage.modelId) else text(R.string.portable_setup_off)), style = MaterialTheme.typography.bodyMedium) }
                current.voice?.let { stage -> Text(text(R.string.portable_setup_voice) + ": " +
                    (if (stage.enabled) stage.voiceId ?: stage.choice.label else text(R.string.portable_setup_off)), style = MaterialTheme.typography.bodyMedium) }
                val currentSource = current.speech?.sourceLanguage ?: current.translation?.sourceLanguage
                if (currentSource != null) {
                    val speechSources = current.speech?.model?.let { ref -> ref.catalogId?.let(SpeechArtifactCatalog::find)?.languages
                        ?: ref.profile?.let { runCatching { SpeechProfile.fromId(it).capabilities.sourceLanguages }.getOrNull() } }
                    val translationSources = current.translation?.takeIf { it.enabled }?.let { TranslationOptions.sourceLanguages(it.modelId) }.orEmpty()
                    val choices = (if (translationSources.isNotEmpty()) translationSources.intersect(speechSources ?: translationSources)
                        else speechSources ?: LanguageCatalog.whisperCodes).toMutableSet().apply { add(currentSource) }
                    ModelFilterPicker(text(R.string.portable_setup_source), currentSource,
                        choices.sorted().map { it to if (it == "auto") text(R.string.portable_setup_auto) else TranslationLanguages.label(it) },
                        { source -> setup = current.copy(speech = current.speech?.copy(sourceLanguage = source),
                            translation = current.translation?.copy(sourceLanguage = source)) }, enabled = !busy)
                }
                current.translation?.let { stage ->
                    val targets = TranslationOptions.targetLanguages(stage.modelId, stage.sourceLanguage) + stage.targetLanguage
                    ModelFilterPicker(text(R.string.portable_setup_target), stage.targetLanguage, targets.sorted().map { it to TranslationLanguages.label(it) },
                        { target -> setup = current.copy(translation = stage.copy(targetLanguage = target)) }, enabled = !busy)
                    if (stage.modelId in setOf("ct2-jisr-50m-en-ar", "ct2-jisr-50m-en-ar-int8")) com.sal7one.transiber.settings.JisrDialectPicker(stage.arabicDialect ?: "ar", !busy) {
                        setup = current.copy(translation = stage.copy(arabicDialect = it))
                    }
                }
                if (preview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                preview?.let { state ->
                    Text(text(if (state.ready) R.string.portable_setup_ready else R.string.portable_setup_needs_models),
                        style = MaterialTheme.typography.titleSmall)
                    state.missingSpeechCatalogId?.let { Text(text(R.string.portable_setup_missing, SpeechArtifactCatalog.find(it)?.label ?: it)) }
                    state.missingTranslationIds.forEach { Text(text(R.string.portable_setup_missing, TranslationOptions.label(it))) }
                    state.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = { job { actions.apply(current); notice = text(R.string.portable_setup_applied) } },
                        enabled = !busy && state.ready, modifier = Modifier.fillMaxWidth()) { Text(text(R.string.portable_setup_apply)) }
                    if (state.missingSpeechCatalogId != null || state.missingTranslationIds.isNotEmpty()) {
                        if (ByokPolicy.FEATURE_BYOK) OutlinedButton(onClick = { job {
                            actions.downloadMissing(current); notice = text(R.string.portable_setup_queued); onDownloads()
                        } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(text(R.string.portable_setup_download)) }
                        else Text(text(R.string.portable_setup_offline), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pendingJson = current.toJson(); exporter.launch("hearth-setup.json") }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text(text(R.string.portable_setup_export)) }
                    TextButton(onClick = { revision++ }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(text(R.string.portable_setup_recheck)) }
                }
                current.speech?.let { TextButton(onClick = onSpeechModels) { Text(text(R.string.portable_setup_manage_speech)) } }
                current.translation?.let { TextButton(onClick = onTranslationModels) { Text(text(R.string.portable_setup_manage_translation)) } }
                current.voice?.let { TextButton(onClick = onVoices) { Text(text(R.string.portable_setup_manage_voice)) } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (exportScope) AlertDialog(onDismissRequest = { exportScope = false }, title = { Text(text(R.string.portable_setup_current)) },
        text = { Column {
            Text(text(R.string.portable_setup_include))
            Row { Checkbox(includeSpeech, { includeSpeech = it }); Text(text(R.string.portable_setup_speech), Modifier.padding(top = 12.dp)) }
            Row { Checkbox(includeTranslation, { includeTranslation = it }); Text(text(R.string.portable_setup_translation), Modifier.padding(top = 12.dp)) }
            Row { Checkbox(includeVoice, { includeVoice = it }); Text(text(R.string.portable_setup_voice), Modifier.padding(top = 12.dp)) }
        } }, confirmButton = { TextButton(onClick = {
            exportScope = false
            job { preview = null; setup = actions.exportCurrent(includeSpeech = includeSpeech,
                includeTranslation = includeTranslation, includeVoice = includeVoice); revision++ }
        }, enabled = includeSpeech || includeTranslation || includeVoice) { Text(text(R.string.portable_setup_prepare)) } },
        dismissButton = { TextButton(onClick = { exportScope = false }) { Text(text(R.string.portable_setup_cancel)) } })
}
