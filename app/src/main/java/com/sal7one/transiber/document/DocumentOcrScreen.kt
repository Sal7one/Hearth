package com.sal7one.transiber.document

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.i18n.label
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.rememberOcrPreferences
import com.sal7one.transiber.caption.CaptionLanguageChoices
import com.sal7one.transiber.caption.LanguagePickerContent
import com.sal7one.transiber.i18n.languageName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** File import stays on-device; cloud translation is not implicitly selected or sent document bytes. */
@Composable
internal fun DocumentOcrScreen(onOcrModels: () -> Unit, onVoices: () -> Unit = {}, onTranslate: ((String) -> Unit)? = null) {
    val text = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model: DocumentOcrViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val (ocr, preferences) = rememberOcrPreferences()
    val documentPreferences = remember(context) { context.getSharedPreferences("document_ocr", android.content.Context.MODE_PRIVATE) }
    var documentProfileId by rememberSaveable { mutableStateOf(documentPreferences.getString("profile_v1", null)
        ?.takeIf { id -> OcrCatalog.documentProfiles.any { it.id == id } } ?: ocr.profile.id) }
    val documentProfile = OcrCatalog.documentProfiles.firstOrNull { it.id == documentProfileId } ?: OcrCatalog.profile("latin")
    var documentLanguage by rememberSaveable { mutableStateOf(DocumentTextLanguage.read(
        documentPreferences.getString("text_language_v1", null), documentProfile.id, documentProfile.languages)) }
    var chooseLanguage by remember { mutableStateOf(false) }
    var scanImages by rememberSaveable { mutableStateOf(true) }
    var chooseProfile by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var speaking by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val voice = remember(context) { com.sal7one.transiber.voice.VoicePlayer(context) { active, error -> speaking = active; voiceError = error } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { exportStatus = null; model.open(uri, documentProfile, scanImages) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try { model.export(uri); exportStatus = text(R.string.document_exported) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { model.fail(e) }
        }
    }
    DisposableEffect(model, voice) { onDispose {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        if (activity?.isChangingConfigurations != true) model.cancel()
        voice.close()
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Description, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(text(R.string.document_title), style = MaterialTheme.typography.headlineLarge)
                    Text(text(R.string.document_subtitle), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*")) },
                        enabled = !state.processing, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                        Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(text(R.string.document_open))
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { chooseProfile = true }, enabled = !state.processing) { Text(text.label(documentProfile)) }
                        DropdownMenu(expanded = chooseProfile, onDismissRequest = { chooseProfile = false }) {
                            OcrCatalog.documentProfiles.filter { it.engine != "glm" || android.os.Process.is64Bit() }.forEach { profile ->
                                DropdownMenuItem(text = { Text(text.label(profile)) }, onClick = {
                                    documentProfileId = profile.id
                                    documentLanguage = DocumentTextLanguage.defaultFor(profile.id, profile.languages)
                                    documentPreferences.edit().putString("profile_v1", profile.id)
                                        .putString("text_language_v1", documentLanguage).apply()
                                    chooseProfile = false
                                })
                            }
                        }
                    }
                    if (documentProfile.engine == "glm") Text(text(R.string.vision_ocr_warning),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { chooseLanguage = true }, enabled = !state.processing) {
                        Text("${text(R.string.vision_document_language)}: ${text.languageName(documentLanguage)}")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text(R.string.document_embedded_images), Modifier.weight(1f))
                        Switch(checked = scanImages, onCheckedChange = { scanImages = it }, enabled = !state.processing)
                    }
                    TextButton(onClick = onOcrModels, enabled = !state.processing) { Text(text(R.string.document_models)) }
                    Text(text(R.string.document_local_policy), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.name.isNotEmpty()) item {
            Text(state.name, style = MaterialTheme.typography.titleLarge)
            if (state.processing) {
                if (state.total > 0) {
                    LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                    Text(text(R.string.document_progress, state.completed, state.total))
                } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(text(R.string.document_preparing)) }
                OutlinedButton(onClick = model::cancel) { Text(text(R.string.document_cancel)) }
            }
            if (state.cancelled) Text(text(R.string.document_cancelled), color = MaterialTheme.colorScheme.secondary)
        }
        (voiceError ?: state.error)?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } }
        if (speaking) item { OutlinedButton(onClick = voice::stop) { Text(text(R.string.document_stop_voice)) } }
        if (state.pages.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { export.launch("${state.name.substringBeforeLast('.').ifBlank { "Hearth-document" }}.txt") }, enabled = !state.processing) {
                    Text(text(R.string.document_export))
                }
                OutlinedButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(state.name, state.text)) }) {
                    Text(text(R.string.document_copy))
                }
            }
            exportStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        itemsIndexed(state.pages) { index, page ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (page.label == "DOCX") text(R.string.document_docx_text) else text(R.string.document_page, page.label), style = MaterialTheme.typography.titleMedium)
                    Text(if (page.fromOcr) text(R.string.document_ocr_time, page.ocrMs) else text(R.string.document_original_text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(page.text.ifBlank { text(R.string.document_empty_page) }) }
                    if (page.text.isNotBlank()) {
                        com.sal7one.transiber.voice.ReadAloudButtons(text(R.string.document_page, page.label),
                            enabled = page.text.length <= 5_000,
                            onPlay = { system -> voiceError = null; voice.speak(page.text, documentLanguage,
                                if (system) com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM) }, onSetup = onVoices)
                        if (onTranslate != null) TextButton(onClick = { voice.stop(); onTranslate(page.text) }, enabled = page.text.length <= 32_000) {
                            Text(text(R.string.document_translate_page))
                        }
                    }
                }
            }
        }
    }
    if (chooseLanguage) Dialog(onDismissRequest = { chooseLanguage = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            LanguagePickerContent(title = text(R.string.vision_document_language),
                choices = CaptionLanguageChoices(DocumentTextLanguage.choices, text(R.string.vision_document_language_help)),
                selected = documentLanguage, onSelect = { code ->
                    documentLanguage = code
                    documentPreferences.edit().putString("text_language_v1", code).apply()
                    chooseLanguage = false
                }, onDismiss = { chooseLanguage = false })
        }
    }
}
