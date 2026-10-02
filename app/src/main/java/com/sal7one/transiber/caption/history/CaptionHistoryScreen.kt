package com.sal7one.transiber.caption.history

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.R
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.i18n.rememberUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
internal fun CaptionHistoryOptions(config: CaptionOverlayConfig,
    onChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit, onHistory: (() -> Unit)? = null) {
    val text = rememberUiText()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text(R.string.caption_history_save), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = config.saveCaptionHistory, onCheckedChange = { enabled -> onChange { it.copy(saveCaptionHistory = enabled) } })
    }
    Text(text(R.string.caption_history_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (onHistory != null) OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text(text(R.string.caption_history_title)) }
}

@Composable
internal fun CaptionHistorySettings(onHistory: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect { config = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    config?.let { current -> CaptionHistoryOptions(current, { change ->
        if (!saving) scope.launch {
            saving = true
            try { CaptionConfigStore.update(context, change); CaptionHistoryRepository.get(context).clearSavingError(); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { saving = false }
        }
    }, onHistory) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptionHistoryScreen() {
    val context = LocalContext.current
    val text = rememberUiText()
    val scope = rememberCoroutineScope()
    val repo = remember { CaptionHistoryRepository.get(context) }
    val revision by repo.revision.collectAsStateWithLifecycle()
    val savingError by repo.savingError.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<CaptionSessionSummary>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SavedCaptionSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var deletion by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportJson by rememberSaveable { mutableStateOf(false) }
    val originalLabel = text(R.string.caption_history_original)
    val translationLabel = text(R.string.caption_history_translation)
    val unavailableLabel = text(R.string.caption_history_no_original)
    fun data(session: SavedCaptionSession, json: Boolean) = if (json) session.toJson() else session.toText(originalLabel, translationLabel, unavailableLabel)
    fun action(block: suspend () -> Unit) {
        if (busy || exportId != null) return
        scope.launch {
            busy = true; error = null; message = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    val finishExport: (android.net.Uri?) -> Unit = { uri ->
        val id = exportId; val json = exportJson
        exportId = null; busy = false
        if (uri != null && id != null) action {
            withContext(Dispatchers.IO) {
                val payload = data(repo.session(id), json).toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload) }
                    ?: kotlin.error("Unable to write the selected file")
            }
            message = text(R.string.caption_history_exported)
        }
    }
    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), finishExport)
    val exportJsonFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), finishExport)
    fun saveFile(session: SavedCaptionSession, json: Boolean) {
        if (busy || exportId != null) return
        exportId = session.summary.id; exportJson = json; busy = true
        try { (if (json) exportJsonFile else exportText).launch("hearth-captions-${session.summary.created}.${if (json) "json" else "txt"}") }
        catch (e: Exception) { exportId = null; busy = false; error = e.message ?: e.toString() }
    }
    fun share(session: SavedCaptionSession) = action {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "caption-exports").apply { check(mkdirs() || isDirectory) }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000L }?.forEach { it.delete() }
            val payload = data(session, false).toByteArray(Charsets.UTF_8)
            check(directory.listFiles().orEmpty().sumOf { it.length() } + payload.size <= 128L * 1024 * 1024) {
                "Caption share cache is full. Save TXT instead."
            }
            File(directory, "hearth-captions-${session.summary.id}-${UUID.randomUUID()}.txt").apply { writeBytes(payload) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("Hearth captions", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, text(R.string.caption_history_share)))
    }
    LaunchedEffect(revision, selectedId) {
        try {
            delay(150) // Coalesce text writes; never query on every audio chunk/partial.
            sessions = repo.list()
            val id = selectedId
            selected = if (id != null && sessions.any { it.id == id }) repo.session(id) else null
            if (id != null && selected == null) selectedId = null
            loading = false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loading = false; error = e.message ?: e.toString() }
    }
    BackHandler(selectedId != null) { selectedId = null; selected = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(text(R.string.caption_history_title), style = MaterialTheme.typography.headlineMedium)
            CaptionHistorySettings()
            Text(text(R.string.caption_history_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            savingError?.let { Text(text(R.string.caption_history_error, it), color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val session = selected
        if (session != null && session.summary.id == selectedId) {
            item {
                Text(session.summary.title.ifBlank { text(R.string.caption_history_title) }, style = MaterialTheme.typography.titleLarge)
                Text(DateFormat.getDateTimeInstance().format(Date(session.summary.created)))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedId = null; selected = null }) { Text(text(R.string.caption_history_back)) }
                    TextButton(enabled = !busy, onClick = { title = session.summary.title; renaming = true }) { Text(text(R.string.caption_history_rename)) }
                    TextButton(enabled = !busy, onClick = { deletion = session.summary.id }) { Text(text(R.string.ui_delete_f6fdb)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { share(session) }) { Text(text(R.string.caption_history_share)) }
                    OutlinedButton(enabled = !busy, onClick = { saveFile(session, false) }) { Text(text(R.string.caption_history_save_text)) }
                    OutlinedButton(enabled = !busy, onClick = { saveFile(session, true) }) { Text(text(R.string.caption_history_save_json)) }
                }
            }
            items(session.lines, key = { it.id }) { line ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(line.created)), style = MaterialTheme.typography.labelSmall)
                            Text(originalLabel + line.sourceLanguage?.let { " · $it" }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(line.original.ifBlank { unavailableLabel }, style = MaterialTheme.typography.bodyLarge)
                            if (line.translation.isNotBlank()) {
                                HorizontalDivider()
                                Text(translationLabel + line.translationLanguage?.let { " · $it" }.orEmpty(), color = MaterialTheme.colorScheme.primary)
                                Text(line.translation, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        } else {
            item {
                if (sessions.isEmpty() && !loading) Text(text(R.string.caption_history_empty))
                if (sessions.isNotEmpty()) TextButton(enabled = !busy, onClick = { deletion = "*" }) { Text(text(R.string.caption_history_delete_all)) }
            }
            items(sessions, key = { it.id }) { summary ->
                OutlinedCard(onClick = { selectedId = summary.id; selected = null }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(summary.title.ifBlank { text(R.string.caption_history_title) }, style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(summary.created)), style = MaterialTheme.typography.labelMedium)
                        Text(text(R.string.caption_history_line_count, summary.count), color = MaterialTheme.colorScheme.primary)
                        Text(summary.preview, maxLines = 2)
                    }
                }
            }
        }
    }
    deletion?.let { id -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text(text(R.string.caption_history_delete_title)) },
        text = { Text(text(R.string.caption_history_delete_detail)) }, confirmButton = { TextButton(onClick = {
            deletion = null
            action { repo.delete(id.takeUnless { it == "*" }); if (id == "*" || id == selectedId) { selectedId = null; selected = null } }
        }) { Text(text(R.string.ui_delete_f6fdb)) } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text(text(R.string.ui_cancel_77dfd)) } }) }
    if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text(text(R.string.caption_history_rename)) },
        text = { OutlinedTextField(title, { title = it.take(120) }, singleLine = true) }, confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { renaming = false; selectedId?.let { id -> action { repo.rename(id, title) } } }) { Text(text(R.string.ui_save_efc00)) }
        }, dismissButton = { TextButton(onClick = { renaming = false }) { Text(text(R.string.ui_cancel_77dfd)) } })
}
