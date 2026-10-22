package com.sal7one.transiber.benchmark

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import android.provider.OpenableColumns
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.lifecycle.repeatOnLifecycle
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionLanguageChoices
import com.sal7one.transiber.caption.LanguagePickerContent
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.PlatformTranslation
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.common_jni.translation.TranslationCatalog
import kotlinx.coroutines.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Device benchmark workspace. The foreground controller owns runs across navigation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalBenchmarkScreen(onModels: (String) -> Unit = {}, onDownloads: () -> Unit = {}) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) {
        Column(Modifier.padding(16.dp)) { com.sal7one.transiber.ui.components.LocalModelPolicyNotice() }
        return
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val runner = remember { LocalBenchmarkRunner(context.applicationContext) }
    val store = remember { BenchmarkResults(context.applicationContext) }
    val saudiStore = remember { SaudiBenchmarkPack(java.io.File(context.filesDir, "benchmark-saudi")) }
    val downloads = remember { FileDownloads(context.applicationContext) }
    var candidates by remember { mutableStateOf<List<BenchmarkCandidate>>(emptyList()) }
    var downloadRecords by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    var results by remember { mutableStateOf<List<BenchmarkResult>>(emptyList()) }
    var suite by remember { mutableStateOf<BenchmarkSuite?>(null) }
    var saudiSuite by remember { mutableStateOf<BenchmarkSuite?>(null) }
    var clip by remember { mutableStateOf<BenchmarkAudio.Clip?>(null) }
    var clipName by remember { mutableStateOf("") }
    var mode by rememberBenchmarkString("mode", "Speech")
    var preset by rememberBenchmarkEnum("preset", BenchmarkPreset.SPEED)
    var selected by rememberBenchmarkSelection("selected-$mode")
    var source by rememberBenchmarkString("source", "ar")
    var target by rememberBenchmarkString("target", "en")
    var text by rememberBenchmarkString("text", "")
    var referenceText by rememberBenchmarkString("referenceText", "")
    var useBuiltInSet by rememberBenchmarkBoolean("useBuiltInSet", true)
    var useSaudiSet by rememberBenchmarkBoolean("useSaudiSet", false)
    var recordingStop by remember { mutableStateOf<AtomicBoolean?>(null) }
    var saudiPrompt by rememberBenchmarkInt("saudiPrompt", 0)
    var compareAcceleration by rememberBenchmarkBoolean("compareAcceleration", false)
    var extendedSpeech by rememberBenchmarkBoolean("extendedSpeech", false)
    var section by rememberBenchmarkString("section", "Setup")
    var showAdvanced by rememberBenchmarkBoolean("showAdvanced", false)
    var modelQuery by rememberBenchmarkString("modelQuery", "")
    var compatibleOnly by rememberBenchmarkBoolean("compatibleOnly", true)
    var catalogueOpen by rememberSaveable { mutableStateOf(false) }
    var catalogueSelection by rememberBenchmarkSelection("catalogueSelection-$mode")
    var pendingCatalogueSelection by rememberBenchmarkSelection("pendingCatalogueSelection-$mode")
    var catalogue by remember { mutableStateOf<List<BenchmarkCatalogueEntry>>(emptyList()) }
    var catalogCandidates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val queueState by BenchmarkDownloadQueue.state.collectAsState()
    val queueing = queueState.registering
    val queueStatus = queueState.message
    var freeBytes by remember { mutableLongStateOf(0L) }
    var resumeCompleted by rememberBenchmarkBoolean("resumeCompleted", false)
    var measuredPasses by rememberBenchmarkInt("measuredPasses", 3)
    var tableSort by rememberBenchmarkEnum("tableSort", BenchmarkTableSort.WARM)
    var picker by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val runState by BenchmarkRunController.state.collectAsState()
    val running = runState.running
    var feedback by remember { mutableStateOf("") }
    val status = runState.status.ifBlank { feedback }
    var error by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) feedback = uiText(UiR.string.benchmark_lab_notification_disabled)
    }
    var refresh by remember { mutableIntStateOf(0) }
    val gateOwner by LocalWorkGate.owner.collectAsState()
    fun stop() = BenchmarkRunController.stop()
    fun startBatches(batches: List<BenchmarkBatch>, title: String = uiText(UiR.string.benchmark_lab_run_title)) {
        try {
            error = null
            feedback = ""
            BenchmarkRunController.start(context, batches, title)
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    LaunchedEffect(runState.revision) {
        try { results = withContext(Dispatchers.IO) { store.load() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val currentRunning by rememberUpdatedState(running)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) recordingStop?.set(true)
            if (event == Lifecycle.Event.ON_RESUME && !currentRunning) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recordingStop?.set(true) }
    }
    LaunchedEffect(lifecycle, downloads) {
        if (ByokPolicy.FEATURE_BYOK) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previousInstalled: Set<Long>? = null
            while (isActive) {
                try {
                    val current = withContext(Dispatchers.IO) { downloads.list() }
                    val installedIds = current.filter(FileDownload::installed).map(FileDownload::id).toSet()
                    if (previousInstalled != null && (installedIds - checkNotNull(previousInstalled)).isNotEmpty()) refresh++
                    previousInstalled = installedIds
                    downloadRecords = current
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: e.toString() }
                delay(1000)
            }
        }
    }
    LaunchedEffect(refresh, mode) {
        try {
            busy = true
            suite = withContext(Dispatchers.IO) { BenchmarkSuite.bundled(context) }
            try { saudiSuite = withContext(Dispatchers.IO) { saudiStore.load() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { saudiSuite = null; error = e.message ?: e.toString() }
            candidates = runner.candidates()
            val catalogueState = withContext(Dispatchers.IO) {
                val registry = com.sal7one.transiber.models.ModelRegistry.getInstance(context).registeredModels.value
                val publisher = com.sal7one.transiber.caption.LocalSpeechModels(java.io.File(context.filesDir, "speech-models")).list()
                val snapshot = com.sal7one.transiber.models.SpeechCatalogInstallations(java.io.File(context.filesDir, "speech-models"))
                    .scan(SpeechArtifactCatalog.all, registry, publisher)
                val mapped = snapshot.registered.mapValues { listOf(it.value.id) } + snapshot.publisher.mapValues { listOf(it.value.id) }
                val translations = candidates.filter { it.targetCodes.isNotEmpty() }.associate { it.id to listOf(it.id) }
                val voices = candidates.filter { it.voiceFamily != null }.groupBy { checkNotNull(it.voiceFamily) }.mapValues { row -> row.value.map { it.id } }
                (mapped + translations + voices) to com.sal7one.transiber.downloads.DownloadStorage.available(context)
            }
            catalogCandidates = catalogueState.first
            freeBytes = catalogueState.second
            val modeTask = when (mode) { "Translation" -> BenchmarkTask.TRANSLATION; "Voices" -> BenchmarkTask.VOICE; else -> BenchmarkTask.SPEECH }
            val currentTaskIds = candidates.filter { it.task == modeTask }.map { it.id }.toSet()
            val newlyReady = pendingCatalogueSelection.flatMap { catalogCandidates[it].orEmpty() }.filter { it in currentTaskIds }
            if (newlyReady.isNotEmpty()) selected = (selected + newlyReady).distinct()
            pendingCatalogueSelection = pendingCatalogueSelection.filterNot { id -> catalogCandidates[id].orEmpty().any { it in currentTaskIds } }
            results = withContext(Dispatchers.IO) { store.load() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
        finally { busy = false }
    }
    LaunchedEffect(mode, refresh, uiText.locale.language) {
        try { catalogue = withContext(Dispatchers.IO) { BenchmarkModelCatalogue.entries(context, mode) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val visible = candidates.filter { it.task == when (mode) {
        "Translation" -> BenchmarkTask.TRANSLATION
        "Voices" -> BenchmarkTask.VOICE
        else -> BenchmarkTask.SPEECH
    } }
    val filteredModels = visible.filter { candidate ->
        (modelQuery.isBlank() || listOf(candidate.label, candidate.route, candidate.id).any { it.contains(modelQuery, true) }) &&
            (!compatibleOnly || (source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")) &&
                (mode != "Translation" || (candidate.translation?.supports(source, target) ?: (target in candidate.targetCodes && source != target))))
    }
    val plan = remember(source, target, preset) { BenchmarkPresets.forPair(source, target).first { it.preset == preset } }
    fun installed(model: SuggestedModel?): BenchmarkCandidate? = model?.let { spec ->
        val ids = catalogCandidates[spec.id].orEmpty()
        candidates.firstOrNull { it.id in ids && source in it.sourceCodes &&
            (spec.kind == "speech" || (it.translation?.supports(source, target) ?: (target in it.targetCodes && source != target))) }
    }
    fun queueModels(models: List<SuggestedModel>) {
        try {
            BenchmarkDownloadQueue.enqueue(context, models)
            pendingCatalogueSelection = (pendingCatalogueSelection + models.map { it.id }).distinct()
            error = null
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    LaunchedEffect(queueState.revision) {
        if (queueState.errors.isNotEmpty()) error = queueState.errors.joinToString("\n")
        refresh++
    }
    fun queue(model: SuggestedModel) = queueModels(listOf(model))
    val activeSpeechSuite = if (source == "ar" && useSaudiSet) saudiSuite else suite
    val builtInCases = remember(suite, saudiSuite, useSaudiSet, mode, source, target, extendedSpeech) {
        (if (mode == "Speech") activeSpeechSuite else suite)
            ?.selected(mode == "Speech", source, target, full = mode == "Speech" && extendedSpeech).orEmpty()
    }
    fun supports(candidate: BenchmarkCandidate) = (source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")) &&
        (mode != "Translation" || (candidate.translation?.supports(source, target)
            ?: (target in candidate.targetCodes && source != target)))
    fun runLanguageSweep(candidate: BenchmarkCandidate) {
        val activeSuite = suite ?: return
        val routes = BenchmarkLanguageSweep.routes(candidate, activeSuite).filter { route ->
            route.target.isNotEmpty() || route.source != "ar" || saudiSuite?.selected(true, "ar", "", false)?.isNotEmpty() == true
        }
        if (routes.isEmpty()) return
        startBatches(routes.map { route ->
            val inputSuite = if (route.target.isEmpty() && route.source == "ar") checkNotNull(saudiSuite) else activeSuite
            BenchmarkBatch(listOf(candidate), route.source, route.target, compareAcceleration = compareAcceleration,
                resumeCompleted = resumeCompleted, measuredPasses = measuredPasses, suite = inputSuite,
                cases = inputSuite.selected(route.target.isEmpty(), route.source, route.target, false))
        }, uiText(UiR.string.benchmark_lab_sweep_title))
    }
    val selectedCompatible = visible.filter { it.id in selected && supports(it) }
    val selectedCount = selectedCompatible.size
    val leaders = remember(results, mode, source, target) {
        BenchmarkComparison.latest(results, source, if (mode == "Translation") target else "",
            task = when (mode) { "Voices" -> "voice"; "Translation" -> "translation"; else -> "speech" })
    }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uiText(UiR.string.ui_selected_recording_39702)
                    val decoded = context.contentResolver.openInputStream(uri)?.use(BenchmarkAudio::read)
                        ?: kotlin.error(uiText(UiR.string.ui_cannot_open_selected_wav_98fe8))
                    name to decoded
                }
                clipName = loaded.first; clip = loaded.second
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    fun startSaudiRecording() {
        val stop = AtomicBoolean(false)
        recordingStop = stop
        scope.launch {
            error = null
            try {
                clip = SaudiBenchmarkRecorder.capture(stop)
                clipName = uiText(UiR.string.benchmark_saudi_recording)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { recordingStop = null }
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSaudiRecording() else error = uiText(UiR.string.benchmark_saudi_mic_permission)
    }
    var exportPayload by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = exportPayload ?: store.export()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) }
                        ?: kotlin.error(uiText(UiR.string.ui_cannot_write_benchmark_results_a2d0d))
                }
                feedback = uiText(UiR.string.ui_results_exported_including_transcripts_8df04)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { exportPayload = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text(uiText(UiR.string.benchmark_lab_title), style = MaterialTheme.typography.headlineMedium)
            Text(uiText(UiR.string.benchmark_lab_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Setup", "Models", "Results").forEach { destination ->
                    FilterChip(section == destination, { section = destination }, label = {
                        Text(when (destination) {
                            "Models" -> uiText(UiR.string.benchmark_lab_models_count, selectedCount)
                            "Results" -> uiText(UiR.string.benchmark_lab_results_count, results.size)
                            else -> uiText(UiR.string.benchmark_lab_setup)
                        })
                    })
                }
            }
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Text(uiText(UiR.string.benchmark_lab_notification_disabled), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(::stop) { Text(if (runState.stopping) uiText(UiR.string.benchmark_lab_stopping) else uiText(UiR.string.benchmark_lab_stop)) }
            }
            runState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            if (queueing || downloadRecords.any(FileDownload::active)) {
                Text(queueStatus.ifBlank { uiText(UiR.string.benchmark_lab_active_downloads, downloadRecords.count(FileDownload::active)) }, style = MaterialTheme.typography.bodySmall)
                if (ByokPolicy.FEATURE_BYOK) TextButton(onDownloads) { Text(uiText(UiR.string.benchmark_lab_download_queue)) }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Speech", "Translation", "Voices").forEach { label ->
                    FilterChip(mode == label, { mode = label; if (mode == "Translation" && source == target) target = if (source == "en") "ar" else "en" }, enabled = !running && !busy, label = { Text(uiText.modelSection(label)) })
                }
            }
        }
        if (section == "Setup") item {
            Text(uiText(UiR.string.benchmark_lab_input_heading), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ picker = "source" }, enabled = !running && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_1_s_language_2_s_a760d, if (mode == "Speech") uiText(UiR.string.language_spoken) else if (mode == "Voices") uiText(UiR.string.benchmark_lab_voice_language) else uiText(UiR.string.language_source), uiText.languageLabel(LanguageCatalog.option(source))))
            }
            if (mode == "Translation") OutlinedButton({ picker = "target" }, enabled = !running && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(uiText(UiR.string.ui_translate_to_1_s_f70dc, uiText.languageLabel(LanguageCatalog.option(target)))) }
            Text(if (mode == "Speech") uiText(UiR.string.ui_forced_language_is_used_where_supported_vosk_always_uses_its_mode_86f53)
                else if (mode == "Voices") uiText(UiR.string.benchmark_lab_voice_language_hint)
                else uiText(UiR.string.ui_compare_the_same_corrected_source_text_separately_from_recognitio_7e2d4), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (section == "Setup") item {
            if (mode != "Voices") Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(compareAcceleration, { compareAcceleration = it }, enabled = !running && !busy)
                Text(uiText(UiR.string.benchmark_compare_acceleration), style = MaterialTheme.typography.bodyMedium)
            }
            if (compareAcceleration && mode != "Voices") Text(uiText(UiR.string.benchmark_compare_acceleration_hint), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(showAdvanced, { showAdvanced = it }, enabled = !running)
                Text(uiText(UiR.string.benchmark_lab_advanced))
            }
            if (showAdvanced) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(resumeCompleted, { resumeCompleted = it }, enabled = !running)
                    Text(uiText(UiR.string.benchmark_lab_resume))
                }
                Text(uiText(UiR.string.benchmark_lab_passes, measuredPasses), style = MaterialTheme.typography.labelLarge)
                Slider(measuredPasses.toFloat(), { measuredPasses = it.roundToInt() }, valueRange = 3f..10f, steps = 6, enabled = !running)
                Text(uiText(if (mode == "Voices") UiR.string.benchmark_lab_voice_execution_hint else UiR.string.benchmark_lab_execution_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (section == "Results") item {
            BenchmarkResultsTable(leaders, source, if (mode == "Translation") target else "",
                tableSort, { tableSort = it }, results = results, task = when (mode) { "Voices" -> "voice"; "Translation" -> "translation"; else -> "speech" },
                onExport = { rows, format ->
                    scope.launch {
                        try {
                            exportPayload = withContext(Dispatchers.IO) { BenchmarkExport.render(rows, format) }
                            export.launch("hearth-benchmarks-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.${format.extension}")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                    }
                })
        }
        if (section == "Setup" && showAdvanced && mode != "Voices") item {
            Text(uiText(UiR.string.benchmark_presets_title), style = MaterialTheme.typography.titleLarge)
            Text(uiText(UiR.string.benchmark_presets_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BenchmarkPreset.entries.forEach { choice ->
                    FilterChip(preset == choice, { preset = choice }, enabled = !running && !busy,
                        label = { Text(uiText(when (choice) {
                            BenchmarkPreset.SPEED -> UiR.string.benchmark_preset_speed
                            BenchmarkPreset.BALANCED -> UiR.string.benchmark_preset_balanced
                            BenchmarkPreset.QUALITY -> UiR.string.benchmark_preset_quality
                        })) })
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Speech" to plan.speech, "Translation" to plan.translation).forEach { (stage, model) ->
                        Text(uiText.modelSection(stage), style = MaterialTheme.typography.titleMedium)
                        if (model == null) Text(uiText(UiR.string.benchmark_no_preset_model), style = MaterialTheme.typography.bodySmall)
                        else {
                            Text(model.label, style = MaterialTheme.typography.bodyMedium)
                            val ready = installed(model)
                            val transfer = BenchmarkDownloadProgress.forModel(model, downloadRecords)
                            Text(when {
                                ready != null -> uiText(UiR.string.benchmark_installed_ready)
                                model.kind == "mlkit" -> uiText(UiR.string.benchmark_mlkit_packs_needed)
                                transfer == null -> uiText(UiR.string.benchmark_download_size,
                                    model.bytes?.div(1_048_576)?.toString() ?: "—")
                                else -> {
                                    val phase = when {
                                        transfer.active -> when (transfer.currentPhase) {
                                            "Verifying" -> UiR.string.benchmark_transfer_verifying
                                            "Installing" -> UiR.string.benchmark_transfer_installing
                                            "Downloading" -> UiR.string.benchmark_transfer_downloading
                                            else -> UiR.string.benchmark_transfer_waiting
                                        }
                                        transfer.failed -> UiR.string.benchmark_transfer_failed
                                        transfer.paused -> UiR.string.benchmark_transfer_paused
                                        transfer.complete -> UiR.string.benchmark_transfer_downloaded
                                        else -> UiR.string.benchmark_transfer_waiting
                                    }
                                    uiText(UiR.string.benchmark_transfer_state, uiText(phase), transfer.doneFiles, transfer.expectedFiles)
                                }
                            }, style = MaterialTheme.typography.bodySmall,
                                color = if (transfer?.failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (ready == null && transfer != null) {
                                val total = transfer.total
                                Text(if (total != null) uiText(UiR.string.benchmark_transfer_bytes,
                                    transfer.bytes / 1_048_576, total / 1_048_576,
                                    ((transfer.fraction ?: 0f) * 100).roundToInt())
                                else uiText(UiR.string.benchmark_transfer_bytes_unknown,
                                    transfer.bytes / 1_048_576), style = MaterialTheme.typography.labelSmall)
                                if (transfer.active) {
                                    if (transfer.fraction != null) LinearProgressIndicator(
                                        progress = { transfer.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                transfer.errors.forEach { cause -> Text(cause, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ready != null) Button(onClick = {
                                    com.sal7one.transiber.preferences.UserPreferences.store(context, "benchmark-options").edit()
                                        .putStringSet("selected-$stage", setOf(ready.id)).apply()
                                    if (mode == stage) selected = listOf(ready.id)
                                    mode = stage
                                    useBuiltInSet = true
                                }, enabled = !busy && !running,
                                    modifier = Modifier.semantics { contentDescription = "${uiText(UiR.string.benchmark_test_this)}: ${model.label}" }) {
                                    Text(uiText(UiR.string.benchmark_test_this))
                                }
                                else if (ByokPolicy.FEATURE_BYOK && model.kind != "mlkit") when {
                                    transfer?.active == true -> TextButton(onClick = onDownloads) {
                                        Text(uiText(UiR.string.benchmark_view_downloads))
                                    }
                                    transfer?.complete == true && transfer.records.all(FileDownload::installed) ->
                                        TextButton(onClick = { refresh++ }, enabled = !busy && !running) {
                                            Text(uiText(UiR.string.ui_refresh_56e3b))
                                        }
                                    else -> Button(onClick = { queue(model) }, enabled = !busy && !running,
                                        modifier = Modifier.semantics { contentDescription = "${uiText(UiR.string.benchmark_download_model)}: ${model.label}" }) {
                                        Text(uiText(when {
                                            transfer?.paused == true -> UiR.string.benchmark_transfer_resume
                                            transfer?.failed == true -> UiR.string.benchmark_transfer_retry
                                            transfer?.complete == true -> UiR.string.benchmark_transfer_finish_install
                                            transfer != null -> UiR.string.benchmark_transfer_continue
                                            else -> UiR.string.benchmark_download_model
                                        }))
                                    }
                                }
                                TextButton(onClick = { onModels(stage) }, enabled = !busy && !running,
                                    modifier = Modifier.semantics { contentDescription = "${uiText(UiR.string.benchmark_browse_models)}: ${uiText.modelSection(stage)}" }) {
                                    Text(uiText(UiR.string.benchmark_browse_models))
                                }
                            }
                        }
                    }
                    val speechModel = installed(plan.speech)
                    val translationModel = installed(plan.translation)
                    val speechSamples = activeSpeechSuite?.selected(true, source, target, full = false).orEmpty()
                    val translationSamples = suite?.selected(false, source, target, full = false).orEmpty()
                    if (speechModel != null && translationModel != null && speechSamples.isNotEmpty() && translationSamples.isNotEmpty())
                        Button(onClick = {
                            startBatches(listOf(
                                BenchmarkBatch(listOf(speechModel), source, "", compareAcceleration = compareAcceleration,
                                    resumeCompleted = resumeCompleted, measuredPasses = measuredPasses,
                                    suite = checkNotNull(activeSpeechSuite), cases = speechSamples),
                                BenchmarkBatch(listOf(translationModel), source, target, compareAcceleration = compareAcceleration,
                                    resumeCompleted = resumeCompleted, measuredPasses = measuredPasses,
                                    suite = checkNotNull(suite), cases = translationSamples)
                            ), uiText(UiR.string.benchmark_lab_pair_title))
                        }, enabled = !busy && !running && gateOwner == null, modifier = Modifier.fillMaxWidth()) {
                            Text(uiText(UiR.string.benchmark_test_pair))
                        }
                    if (ByokPolicy.FEATURE_BYOK) TextButton(onDownloads) { Text(uiText(UiR.string.benchmark_view_downloads)) }
                    TextButton({ refresh++ }, enabled = !running && !busy) { Text(uiText(UiR.string.ui_refresh_56e3b)) }
                    if (running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                        TextButton(::stop) { Text(uiText(UiR.string.ui_stop_comparison_7826e)) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (section == "Setup" && mode != "Voices") item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == "Speech" && source == "ar") FilterChip(useBuiltInSet && useSaudiSet,
                    { useBuiltInSet = true; useSaudiSet = true }, enabled = !running && !busy,
                    label = { Text(uiText(UiR.string.benchmark_saudi_set)) })
                FilterChip(useBuiltInSet && !(mode == "Speech" && source == "ar" && useSaudiSet),
                    { useBuiltInSet = true; useSaudiSet = false }, enabled = !running && !busy,
                    label = { Text(if (mode == "Speech" && source == "ar") uiText(UiR.string.benchmark_egyptian_set)
                        else uiText(UiR.string.benchmark_builtin_set)) })
                FilterChip(!useBuiltInSet, { useBuiltInSet = false }, enabled = !running && !busy,
                    label = { Text(uiText(UiR.string.benchmark_custom_input)) })
            }
            if (useBuiltInSet) {
                if (mode == "Speech" && source == "ar" && useSaudiSet) {
                    Text(uiText(UiR.string.benchmark_saudi_explainer), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SaudiBenchmarkPack.prompts.forEachIndexed { index, prompt ->
                            FilterChip(saudiPrompt == index, { saudiPrompt = index; referenceText = prompt },
                                enabled = !running && recordingStop == null && !busy,
                                label = { Text("${index + 1}") },
                                modifier = Modifier.semantics { contentDescription = "${index + 1}. $prompt" })
                        }
                    }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Text(SaudiBenchmarkPack.prompts[saudiPrompt], Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleLarge)
                    }
                    if (referenceText.isBlank()) LaunchedEffect(saudiPrompt) { referenceText = SaudiBenchmarkPack.prompts[saudiPrompt] }
                    if (recordingStop != null) Button({ recordingStop?.set(true) }, Modifier.fillMaxWidth()) {
                        Text(uiText(UiR.string.benchmark_stop_recording))
                    } else Button({
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            startSaudiRecording()
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, Modifier.fillMaxWidth(), enabled = !running && !busy &&
                        (saudiSuite?.cases?.size ?: 0) < SaudiBenchmarkPack.MAX_CLIPS) {
                        Text(uiText(UiR.string.benchmark_record_saudi)) }
                    OutlinedButton({ openAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
                        Modifier.fillMaxWidth(), enabled = !running && !busy && recordingStop == null &&
                            (saudiSuite?.cases?.size ?: 0) < SaudiBenchmarkPack.MAX_CLIPS) {
                        Text(uiText(UiR.string.benchmark_import_saudi))
                    }
                    clip?.let { chosen ->
                        Text("$clipName · ${number(chosen.durationMs / 1000.0)} s", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(referenceText, { if (it.length <= 500) referenceText = it },
                            label = { Text(uiText(UiR.string.benchmark_reference_transcript)) },
                            supportingText = { Text(uiText(UiR.string.benchmark_saudi_reference_hint)) },
                            modifier = Modifier.fillMaxWidth(), enabled = !running && !busy && recordingStop == null)
                        Button({ scope.launch {
                            busy = true; error = null
                            try {
                                saudiSuite = withContext(Dispatchers.IO) { saudiStore.save(chosen, referenceText) }
                                clip = null; clipName = ""
                                saudiPrompt = ((saudiSuite?.cases?.size ?: 0) % SaudiBenchmarkPack.MAX_CLIPS)
                                referenceText = SaudiBenchmarkPack.prompts[saudiPrompt]
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                            finally { busy = false }
                        } }, enabled = !running && !busy && referenceText.isNotBlank()) {
                            Text(uiText(UiR.string.benchmark_save_saudi))
                        }
                    }
                    Text(uiText(UiR.string.benchmark_saudi_saved, saudiSuite?.cases?.size ?: 0),
                        style = MaterialTheme.typography.bodySmall)
                    saudiSuite?.cases?.forEach { case ->
                        Text("${case.id}: ${case.reference}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (saudiStore.hasFiles()) TextButton({ scope.launch {
                        try { withContext(Dispatchers.IO) { saudiStore.clear() }; saudiSuite = null }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                    } }, enabled = !running && !busy) { Text(uiText(UiR.string.benchmark_clear_saudi)) }
                } else Text(uiText(UiR.string.benchmark_suite_summary), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
                if (mode == "Speech" && !(source == "ar" && useSaudiSet)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!extendedSpeech, { extendedSpeech = false }, enabled = !running && !busy,
                        label = { Text(uiText(UiR.string.benchmark_quick_set)) })
                    FilterChip(extendedSpeech, { extendedSpeech = true }, enabled = !running && !busy,
                        label = { Text(uiText(UiR.string.benchmark_long_clip_set)) })
                }
                if (!(mode == "Speech" && source == "ar" && useSaudiSet)) Text(uiText(UiR.string.benchmark_suite_attribution),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (builtInCases.isEmpty()) {
                    Text(if (mode == "Speech" && source == "ar" && useSaudiSet)
                        uiText(UiR.string.benchmark_saudi_empty)
                    else uiText(UiR.string.benchmark_suite_pair_unavailable, source, target),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp))
                    if (!(mode == "Speech" && source == "ar" && useSaudiSet)) TextButton({ useBuiltInSet = false }, enabled = !running && !busy) {
                        Text(uiText(UiR.string.benchmark_custom_input))
                    }
                }
            }
            if (mode == "Speech") {
                if (!useBuiltInSet) {
                    OutlinedButton({ openAudio.launch(arrayOf("audio/*", "application/octet-stream")) }, enabled = !running && !busy,
                        modifier = Modifier.fillMaxWidth()) { Text(if (clip == null) uiText(UiR.string.ui_choose_wav_recording_b856e) else uiText(UiR.string.ui_change_recording_d75fb)) }
                    Text(if (clip == null) uiText(UiR.string.ui_0_5_30_seconds_pcm16_wav_mono_or_stereo_8_48_khz_2da63) else
                        uiText(UiR.string.ui_1_s_2_s_seconds_b64af, clipName, number(checkNotNull(clip).durationMs / 1000.0)), modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                if (!useBuiltInSet) {
                    OutlinedTextField(text, { if (it.length <= 500) text = it }, label = { Text(uiText(UiR.string.ui_corrected_source_text_165c4)) },
                        placeholder = { Text(uiText(UiR.string.ui_paste_a_short_sentence_in_the_chosen_source_language_5bce5)) },
                        supportingText = { Text(uiText(UiR.string.ui_1_s_500_characters_b6d2a, text.length)) }, enabled = !running,
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                }
            }
            if (!useBuiltInSet) {
                OutlinedTextField(referenceText, { if (it.length <= 2000) referenceText = it },
                    label = { Text(uiText(UiR.string.benchmark_reference_transcript.takeIf { mode == "Speech" } ?: UiR.string.benchmark_reference_translation)) },
                    placeholder = { Text(uiText(UiR.string.benchmark_reference_optional)) }, enabled = !running,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2, maxLines = 5)
                Text(uiText(UiR.string.benchmark_scoring_explainer), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (section == "Setup" && mode == "Voices") item {
            OutlinedTextField(text, { if (it.length <= 500) text = it }, label = { Text(uiText(UiR.string.benchmark_lab_voice_text)) },
                supportingText = { Text(uiText(UiR.string.benchmark_lab_voice_text_hint)) },
                enabled = !running, modifier = Modifier.fillMaxWidth(), minLines = 3)
            if (source in setOf("ar", "en")) TextButton({
                text = if (source == "ar") "مرحباً بك في هيرث. هذا اختبار للأصوات العربية وجودة النطق وسرعة توليد الكلام."
                    else "Welcome to Hearth. This voice test compares clear pronunciation and speech generation speed."
            }, enabled = !running) { Text(uiText(UiR.string.benchmark_lab_sample_sentence)) }
        }
        if (section == "Setup") item {
            Text(uiText(UiR.string.benchmark_lab_choose_heading), style = MaterialTheme.typography.titleLarge)
            Text(uiText(UiR.string.benchmark_lab_selected_hint, selectedCount), style = MaterialTheme.typography.bodyMedium)
            Button({ section = "Models" }, Modifier.fillMaxWidth()) { Text(if (selectedCount == 0) uiText(UiR.string.benchmark_lab_choose) else uiText(UiR.string.benchmark_lab_review_selected, selectedCount)) }
        }
        if (section == "Models") item {
            Text(uiText(UiR.string.benchmark_lab_installed_title), style = MaterialTheme.typography.titleLarge)
            Text(uiText(UiR.string.benchmark_lab_installed_summary, visible.size, selectedCount), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button({ catalogueOpen = true }, Modifier.fillMaxWidth()) { Text(uiText(UiR.string.benchmark_lab_browse)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onModels(mode) }, enabled = !running) { Text(uiText(UiR.string.benchmark_lab_import)) }
                TextButton({ refresh++ }, enabled = !running && !busy) { Text(uiText(UiR.string.benchmark_lab_refresh)) }
            }
            OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text(uiText(UiR.string.benchmark_lab_search_installed)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(compatibleOnly, { compatibleOnly = !compatibleOnly }, label = { Text(uiText(UiR.string.benchmark_lab_compatible)) })
                TextButton({ selected = (selected + filteredModels.filter(::supports).map { it.id }).distinct() },
                    enabled = !running && !busy && filteredModels.any(::supports)) { Text(uiText(UiR.string.benchmark_lab_select_all)) }
                TextButton({ selected = emptyList() }, enabled = !running && selected.isNotEmpty()) { Text(uiText(UiR.string.benchmark_lab_clear_selection)) }
            }
            if (filteredModels.isEmpty()) Text(uiText(UiR.string.benchmark_lab_no_installed), style = MaterialTheme.typography.bodyMedium)
        }
        if (section == "Models") items(filteredModels, key = { it.id }) { candidate ->
            val enabled = !running && !busy && supports(candidate)
            val sweepRoutes = suite?.let { BenchmarkLanguageSweep.routes(candidate, it) }.orEmpty().filter { route ->
                route.target.isNotEmpty() || route.source != "ar" || saudiSuite != null
            }
            val sweepResults = suite?.let { activeSuite -> sweepRoutes.mapNotNull { route ->
                val inputSuite = if (route.target.isEmpty() && route.source == "ar") saudiSuite else activeSuite
                results.firstOrNull { result -> result.identity == candidate.id && result.source == route.source &&
                    result.target == route.target && inputSuite != null &&
                    BenchmarkLanguageSweep.isBundled(result, inputSuite) && result.error == null }
            } }.orEmpty()
            OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .toggleable(candidate.id in selected, enabled = enabled, role = Role.Checkbox) { checked ->
                    selected = if (checked) (selected + candidate.id).distinct() else selected - candidate.id
                }.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(candidate.id in selected, null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(candidate.label)
                    Text(if (supports(candidate)) candidate.route else uiText(UiR.string.ui_not_compatible_with_this_language_selection_aa8b5),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showAdvanced && sweepRoutes.isNotEmpty() && mode != "Voices") {
                TextButton({ runLanguageSweep(candidate) }, enabled = !running && !busy && gateOwner == null,
                    modifier = Modifier.padding(start = 48.dp)) {
                    Text(uiText(UiR.string.benchmark_test_all_languages, sweepRoutes.size))
                }
                Text(sweepRoutes.joinToString(" · ") { route ->
                    if (route.target.isEmpty()) route.source.uppercase(Locale.ROOT)
                    else "${route.source.uppercase(Locale.ROOT)}→${route.target.uppercase(Locale.ROOT)}"
                }, Modifier.padding(start = 48.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sweepResults.isNotEmpty()) {
                    Text(uiText(UiR.string.benchmark_sweep_saved, sweepResults.size, sweepRoutes.size),
                        Modifier.padding(start = 48.dp), style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.padding(start = 48.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sweepResults.forEach { result ->
                            val label = if (result.target.isEmpty()) result.source.uppercase(Locale.ROOT)
                                else "${result.source.uppercase(Locale.ROOT)}→${result.target.uppercase(Locale.ROOT)}"
                            val speedText = BenchmarkTableModel.audioSpeed(result)?.let { "${number(it)}×" }
                                ?: BenchmarkTableModel.translationMsPerSentence(result)?.let { "${number(it)}ms" } ?: "—"
                            val qualityText = BenchmarkTableModel.qualityValue(result)?.let { value ->
                                uiText(if (result.target.isEmpty()) UiR.string.benchmark_quality_errors
                                    else UiR.string.benchmark_quality_similarity, "${number(value * 100)}%")
                            } ?: "—"
                            AssistChip(onClick = {
                                source = result.source
                                if (result.target.isNotEmpty()) target = result.target
                                section = "Results"
                            }, label = { Text("$label · $speedText · $qualityText") }, enabled = !running)
                        }
                    }
                }
            }
            }
        }
        if (section != "Results") item {
            Text(uiText(UiR.string.benchmark_lab_run_heading), style = MaterialTheme.typography.titleLarge)
            Text(uiText(UiR.string.benchmark_lab_run_summary, selectedCount, measuredPasses) + if (compareAcceleration && mode != "Voices") uiText(UiR.string.benchmark_lab_backend_suffix) else "", style = MaterialTheme.typography.bodyMedium)
            if (gateOwner != null && gateOwner != "Benchmark") Text(uiText(UiR.string.ui_stop_1_s_before_comparing_models_fc4d7, gateOwner), color = MaterialTheme.colorScheme.error)
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = uiText(UiR.string.ui_benchmark_in_progress_c6417) })
                Button(::stop, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(uiText(UiR.string.ui_stop_comparison_7826e)) }
            } else Button({
                val chosen = selectedCompatible
                val activeSuite = if (mode == "Speech") activeSpeechSuite else suite
                startBatches(listOf(BenchmarkBatch(chosen, source, if (mode == "Translation") target else "",
                    clip = clip, text = text, referenceText = referenceText.takeIf(String::isNotBlank),
                    compareAcceleration = compareAcceleration, resumeCompleted = resumeCompleted, measuredPasses = measuredPasses,
                    suite = if (useBuiltInSet && mode != "Voices") activeSuite else null,
                    cases = if (useBuiltInSet && mode != "Voices") builtInCases else emptyList())))
                section = "Results"
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy && gateOwner == null && selectedCount > 0 &&
                (if (mode == "Voices") text.isNotBlank() else if (useBuiltInSet) builtInCases.isNotEmpty() else if (mode == "Speech") clip != null else text.isNotBlank() && source != target)) {
                Text(uiText(UiR.string.ui_compare_1_s_models_1916c, selectedCount))
            }
            if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            error?.let { SelectionContainer { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) } }
        }
        if (section == "Setup") item {
            Text(uiText(when (mode) {
                "Voices" -> UiR.string.benchmark_lab_voice_measurement
                "Translation" -> UiR.string.benchmark_lab_translation_measurement
                else -> UiR.string.ui_speed_is_measured_with_accelerated_replay_not_microphone_to_capti_fcf89
            }),
                style = MaterialTheme.typography.bodySmall)
        }
        if (section == "Results" && results.isNotEmpty()) {
            item {
                Text(uiText(UiR.string.ui_saved_results_5e7cc), style = MaterialTheme.typography.titleLarge)
                Text(uiText(UiR.string.ui_stored_only_on_this_device_export_includes_recognized_and_transla_f23f5), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        val name = "hearth-benchmarks-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                        exportPayload = null
                        export.launch(name)
                    }, enabled = !running) { Text(uiText(UiR.string.ui_export_results_fc7d7)) }
                    TextButton({ scope.launch {
                        try { withContext(Dispatchers.IO) { store.clear() }; results = emptyList() }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                    } }, enabled = !running) { Text(uiText(UiR.string.ui_clear_history_53b51)) }
                }
            }
        }
    }
    if (catalogueOpen) BenchmarkCatalogueDialog(catalogue, source, target, catalogCandidates.keys, downloadRecords,
        catalogueSelection, { catalogueSelection = it }, freeBytes, queueing, queueStatus, queueState.errors, ::queueModels,
        onPause = { records -> scope.launch {
            try { withContext(Dispatchers.IO) { records.forEach { downloads.pause(it.id) } }; downloadRecords = withContext(Dispatchers.IO) { downloads.list() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        } }, onDownloads = onDownloads, onModels = { onModels(mode) }, onDismiss = { catalogueOpen = false })
    picker?.let { which ->
        val candidatesForPicker = if (which == "target") visible.filter { source in it.sourceCodes || it.sourceCodes == setOf("model") } else visible
        val codes = (if (which == "target") candidatesForPicker.flatMap { candidate ->
            candidate.targetCodes.filter { code -> candidate.translation?.supports(source, code)
                ?: (code != source && code in candidate.targetCodes) }
        } + TranslationCatalog.models.flatMap { it.targetLanguages.filter { code -> it.supports(source, code) } } +
            MarianPackage.pairs.filter { it.source == source }.map { it.target } +
            (if (PlatformTranslation.available) TranslationOptions.mlKitCodes.filter { it != source } else emptyList())
        else candidatesForPicker.flatMap { it.sourceCodes } + if (mode == "Voices")
            com.sal7one.transiber.voice.LocalVoiceFamilies.providers.flatMap { it.languages } else if (mode == "Speech")
            SpeechArtifactCatalog.all.flatMap { it.languages } else
            TranslationCatalog.models.flatMap { it.sourceLanguages } + MarianPackage.pairs.map { it.source } +
                (if (PlatformTranslation.available) TranslationOptions.mlKitCodes else emptySet()))
            .toSet().let { if (mode == "Translation") it - setOf("auto", "model") else it }
        Dialog({ picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                LanguagePickerContent(if (which == "target") uiText(UiR.string.ui_translate_to_a1ba6) else uiText(UiR.string.ui_source_language_c951f),
                    CaptionLanguageChoices(codes, uiText(UiR.string.benchmark_picker_hint)),
                    if (which == "target") target else source,
                    { if (which == "target") target = it else source = it; picker = null }, { picker = null })
            }
        }
    }
}

private fun number(value: Double) = String.format(Locale.getDefault(), "%.2f", value)
