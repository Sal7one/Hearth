package com.sal7one.transiber.benchmark

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.caption.CaptionLanguageChoices
import com.sal7one.transiber.caption.LanguagePickerContent
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Explicit finite local-only experiments. Leaving/backgrounding this page cancels safely. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalBenchmarkScreen(onModels: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val runner = remember { LocalBenchmarkRunner(context.applicationContext) }
    val store = remember { BenchmarkResults(context.applicationContext) }
    var candidates by remember { mutableStateOf<List<BenchmarkCandidate>>(emptyList()) }
    var results by remember { mutableStateOf<List<BenchmarkResult>>(emptyList()) }
    var clip by remember { mutableStateOf<BenchmarkAudio.Clip?>(null) }
    var clipName by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("Speech") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var source by rememberSaveable { mutableStateOf("en") }
    var target by rememberSaveable { mutableStateOf("ar") }
    var text by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val gateOwner by LocalWorkGate.owner.collectAsState()
    fun stop() { runner.cancel(); job?.cancel(); if (running) status = "Stopping after the current native operation…" }
    DisposableEffect(lifecycle, runner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); runner.cancel(); job?.cancel() }
    }
    LaunchedEffect(refresh) {
        try {
            busy = true
            candidates = runner.candidates()
            results = withContext(Dispatchers.IO) { store.load() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
        finally { busy = false }
    }
    val visible = candidates.filter { it.targetCodes.isNotEmpty() == (mode == "Translation") }
    fun supports(candidate: BenchmarkCandidate) = (source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")) &&
        (mode == "Speech" || (target in candidate.targetCodes && source != target))
    val availableIds = visible.filter(::supports).map { it.id }
    LaunchedEffect(mode, source, target, candidates) {
        selected = selected.filter { it in availableIds }.ifEmpty { availableIds.take(12) }
    }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Selected recording"
                    val decoded = context.contentResolver.openInputStream(uri)?.use(BenchmarkAudio::read)
                        ?: kotlin.error("Cannot open selected WAV")
                    name to decoded
                }
                clipName = loaded.first; clip = loaded.second
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = store.export()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) }
                        ?: kotlin.error("Cannot write benchmark results")
                }
                status = "Results exported, including transcripts."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Compare local models", style = MaterialTheme.typography.headlineSmall)
            Text("Use the same recording or corrected text for each model. Three passes per model; models run one at a time. No cloud requests or automatic downloads.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Speech", "Translation").forEach { label ->
                    FilterChip(mode == label, { mode = label }, enabled = !running && !busy, label = { Text(label) })
                }
            }
        }
        item {
            OutlinedButton({ picker = "source" }, enabled = !running && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("${if (mode == "Speech") "Spoken" else "Source"} language: ${LanguageCatalog.option(source).label}")
            }
            if (mode == "Translation") OutlinedButton({ picker = "target" }, enabled = !running && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Translate to: ${LanguageCatalog.option(target).label}") }
            Text(if (mode == "Speech") "Forced language is used where supported. Vosk always uses its model’s language. Choose a matching recording."
                else "Compare the same corrected source text, separately from recognition errors.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        item {
            if (mode == "Speech") {
                OutlinedButton({ openAudio.launch(arrayOf("audio/*", "application/octet-stream")) }, enabled = !running && !busy,
                    modifier = Modifier.fillMaxWidth()) { Text(if (clip == null) "Choose WAV recording" else "Change recording") }
                Text(if (clip == null) "0.5–30 seconds · PCM16 WAV · mono or stereo · 8–48 kHz" else
                    "$clipName · ${number(checkNotNull(clip).durationMs / 1000.0)} seconds", modifier = Modifier.padding(top = 4.dp))
            } else {
                OutlinedTextField(text, { if (it.length <= 500) text = it }, label = { Text("Corrected source text") },
                    placeholder = { Text("Paste a short sentence in the chosen source language") },
                    supportingText = { Text("${text.length}/500 characters") }, enabled = !running,
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
            }
        }
        item {
            Text("Installed models", style = MaterialTheme.typography.titleMedium)
            if (visible.isEmpty()) Text("No models installed for this comparison. Download or import one in Models.", Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onModels, enabled = !running && !busy) { Text("Open models") }
                TextButton({ refresh++ }, enabled = !running && !busy) { Text("Refresh") }
                if (availableIds.isNotEmpty()) TextButton({ selected = if (selected.isEmpty()) availableIds.take(12) else emptyList() },
                    enabled = !running && !busy) { Text(if (selected.isEmpty()) "Select compatible models" else "Deselect all") }
            }
        }
        items(visible, key = { it.id }) { candidate ->
            val enabled = !running && !busy && supports(candidate)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(candidate.id in selected, enabled = enabled, role = Role.Checkbox) { checked ->
                    selected = if (checked) (selected + candidate.id).take(12) else selected - candidate.id
                }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(candidate.id in selected, null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(candidate.label)
                    Text(if (supports(candidate)) candidate.route else "Not compatible with this language selection",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            if (gateOwner != null && gateOwner != "Benchmark") Text("Stop $gateOwner before comparing models.", color = MaterialTheme.colorScheme.error)
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Benchmark in progress" })
                Button(::stop, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Stop comparison") }
            } else Button({
                val chosen = visible.filter { it.id in selected && supports(it) }
                running = true; error = null
                job = scope.launch {
                    try {
                        runner.run(chosen, clip, text, source, target, { status = it }) { result ->
                            results = (listOf(result) + results).take(40)
                        }
                        status = "Comparison complete. Review accuracy as well as speed."
                    } catch (e: CancellationException) { status = "Comparison stopped. Completed results were saved."; throw e }
                    catch (e: Exception) { error = e.message ?: e.toString() }
                    finally { running = false }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy && gateOwner == null && selected.isNotEmpty() &&
                (if (mode == "Speech") clip != null else text.isNotBlank() && source != target)) { Text("Compare ${selected.size} models") }
            if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            error?.let { SelectionContainer { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) } }
        }
        item {
            Text("Speed is measured with accelerated replay, not microphone-to-caption delay. Loading includes integrity verification; the OS may cache files. Whisper/Vosk use batch inference; native speech packages use the production streaming/window adapter. Compare transcript quality before choosing.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (results.isNotEmpty()) {
            item {
                Text("Saved results", style = MaterialTheme.typography.titleLarge)
                Text("Stored only on this device. Export includes recognized and translated text; the recording is not saved.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ export.launch("hearth-benchmark-results.json") }, enabled = !running) { Text("Export results") }
                    TextButton({ scope.launch {
                        try { withContext(Dispatchers.IO) { store.clear() }; results = emptyList() }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                    } }, enabled = !running) { Text("Clear history") }
                }
            }
            items(results, key = { it.runId + it.identity }) { result -> BenchmarkResultCard(result) }
        }
    }
    picker?.let { which ->
        val codes = (if (which == "target") visible.flatMap { it.targetCodes } else visible.flatMap { it.sourceCodes })
            .toSet().let { if (mode == "Translation") it - setOf("auto", "model") else it }
        Dialog({ picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                LanguagePickerContent(if (which == "target") "Translate to" else "Source language",
                    CaptionLanguageChoices(codes, "Languages advertised by installed models; incompatible models are disabled."),
                    if (which == "target") target else source,
                    { if (which == "target") target = it else source = it; picker = null }, { picker = null })
            }
        }
    }
}

private fun number(value: Double) = String.format(Locale.getDefault(), "%.2f", value)
@Composable
private fun BenchmarkResultCard(result: BenchmarkResult) {
    var details by rememberSaveable(result.runId, result.identity) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(result.model, style = MaterialTheme.typography.titleMedium)
            Text("${result.source}${if (result.target.isBlank()) "" else " → ${result.target}"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.timestamp))}",
                style = MaterialTheme.typography.bodySmall)
            result.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
            Text("Load + verify: ${number(result.loadMs / 1000)} s")
            if (result.computeMs.isNotEmpty()) Text("First inference: ${number(result.computeMs.first() / 1000)} s")
            if (result.computeMs.size >= 3) {
                val warm = BenchmarkMetrics.median(result.computeMs.drop(1))
                Text("Warm median: ${number(warm / 1000)} s")
                if (result.audioMs > 0) Text("Real-time factor: ${number(BenchmarkMetrics.realTimeFactor(warm, result.audioMs))}× (below 1 keeps ahead in replay)")
            }
            val output = result.texts.lastOrNull()
            if (output != null) SelectionContainer { Text(output.ifBlank { "No speech recognized. A fast empty result is not a quality win." }) }
            TextButton({ details = !details }) { Text(if (details) "Hide details" else "All passes and runtime") }
            if (details) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${result.route}\n${result.device}\n${result.runtime}\nModel: ${result.identity}\nInput SHA-256: ${result.inputHash}\nRun: ${result.runId}", style = MaterialTheme.typography.bodySmall)
                    result.computeMs.forEachIndexed { i, time ->
                        Text("Pass ${i + 1}: ${number(time)} ms; first text in replay ${number(result.firstTextMs[i])} ms\n${result.texts[i]}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
