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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    val discoveryIssues by runner.discoveryIssues.collectAsState()
    var showDiscoveryIssues by rememberSaveable { mutableStateOf(false) }
    val store = remember { BenchmarkResults(context.applicationContext) }
    val saudiStore = remember { SaudiBenchmarkPack(java.io.File(context.filesDir, "benchmark-saudi")) }
    val downloads = remember { FileDownloads(context.applicationContext) }
    val customAudioStore = remember { BenchmarkCustomAudioStore(java.io.File(context.filesDir, "benchmark-custom")) }
    val saudiDraftStore = remember { BenchmarkCustomAudioStore(java.io.File(context.filesDir, "benchmark-saudi-draft")) }
    val listState = rememberLazyListState()
    var candidates by remember { mutableStateOf<List<BenchmarkCandidate>>(emptyList()) }
    var downloadRecords by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    var results by remember { mutableStateOf<List<BenchmarkResult>>(emptyList()) }
    var suite by remember { mutableStateOf<BenchmarkSuite?>(null) }
    var saudiSuite by remember { mutableStateOf<BenchmarkSuite?>(null) }
    var clip by remember { mutableStateOf<BenchmarkAudio.Clip?>(null) }
    var clipName by remember { mutableStateOf("") }
    var saudiClip by remember { mutableStateOf<BenchmarkAudio.Clip?>(null) }
    var saudiClipName by remember { mutableStateOf("") }
    var pendingSaudiImport by rememberSaveable { mutableStateOf(false) }
    var restoredAudio by remember { mutableStateOf(false) }
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
    var saudiReferenceText by rememberBenchmarkString("saudiReferenceText",
        referenceText.takeIf { useSaudiSet && source == "ar" }.orEmpty().ifBlank {
            SaudiBenchmarkPack.prompts[saudiPrompt.coerceIn(SaudiBenchmarkPack.prompts.indices)]
        })
    var compareAcceleration by rememberBenchmarkBoolean("compareAcceleration", false)
    var languageMode by rememberBenchmarkEnum("recognition-language-mode", BenchmarkLanguageMode.MATCH_INPUT)
    var useMatrix by rememberBenchmarkBoolean("reference-language-matrix", useBuiltInSet && !useSaudiSet)
    var matrixLanguageCodes by rememberBenchmarkString("reference-language-codes", "en,ru,zh")
    val matrixLanguages = matrixLanguageCodes.split(',').filter { it in BenchmarkLanguageMatrix.languages }.toSet()
    var showSamples by rememberSaveable { mutableStateOf(false) }
    var showSessionHelp by rememberSaveable { mutableStateOf(false) }
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
    var attemptTimeoutSeconds by rememberBenchmarkAttemptTimeout()
    var audioDurations by remember { mutableStateOf<BenchmarkAudioDurations?>(null) }
    var tableSort by rememberBenchmarkEnum("tableSort", BenchmarkTableSort.WARM)
    var picker by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(true) }
    var discovered by remember { mutableStateOf(false) }
    var catalogueRefreshing by remember { mutableStateOf(false) }
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
    fun startBatches(batches: List<BenchmarkBatch>, title: String = uiText(UiR.string.benchmark_lab_run_title)): Boolean {
        return try {
            error = null
            feedback = ""
            val omissions = mutableListOf<String>()
            val executable = batches.map { it.copy(attemptTimeoutSeconds = attemptTimeoutSeconds) }.mapNotNull { batch ->
                if (batch.models.firstOrNull()?.task != BenchmarkTask.SPEECH) batch else {
                    val expanded = BenchmarkLanguagePlan.expand(batch.models, batch.source, languageMode)
                    omissions += expanded.omitted
                    batch.copy(models = expanded.models).takeIf { it.models.isNotEmpty() }
                }
            }
            require(executable.isNotEmpty()) { omissions.joinToString("\n").ifBlank { uiText(UiR.string.benchmark_ready_no_models) } }
            BenchmarkRunController.start(context, executable, title)
            if (omissions.isNotEmpty()) feedback = omissions.joinToString("\n")
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            true
        } catch (e: Exception) { error = e.message ?: e.toString(); false }
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
    // Discovery publishes a usable snapshot independently of catalogue bookkeeping.
    // Returning to this screen must not hash every installed model or lock existing choices.
    LaunchedEffect(refresh) {
        discovering = true
        try {
            suite = withContext(Dispatchers.IO) { BenchmarkSuite.bundled(context) }
            try { saudiSuite = withContext(Dispatchers.IO) { saudiStore.load() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { saudiSuite = null; error = e.message ?: e.toString() }
            if (!restoredAudio) {
                try {
                    val saved = withContext(Dispatchers.IO) { customAudioStore.load() }
                    clip = saved?.clip; clipName = saved?.label.orEmpty()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: e.toString() }
                try {
                    val draft = withContext(Dispatchers.IO) { saudiDraftStore.load() }
                    saudiClip = draft?.clip; saudiClipName = draft?.label.orEmpty()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: e.toString() }
                restoredAudio = true
            }
            candidates = runner.candidates()
            results = withContext(Dispatchers.IO) { store.load() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
        finally { discovering = false; discovered = true }
    }
    LaunchedEffect(candidates, refresh) {
        catalogueRefreshing = true
        try {
            val state = withContext(Dispatchers.IO) {
                BenchmarkInstalledCatalogue.map(candidates, java.io.File(context.filesDir, "speech-models")) to
                    com.sal7one.transiber.downloads.DownloadStorage.available(context)
            }
            catalogCandidates = state.first
            freeBytes = state.second
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
        finally { catalogueRefreshing = false }
    }
    LaunchedEffect(catalogCandidates, pendingCatalogueSelection, mode) {
        val modeTask = when (mode) { "Translation" -> BenchmarkTask.TRANSLATION; "Voices" -> BenchmarkTask.VOICE; else -> BenchmarkTask.SPEECH }
        val currentTaskIds = candidates.filter { it.task == modeTask }.map { it.id }.toSet()
        val newlyReady = pendingCatalogueSelection.flatMap { catalogCandidates[it].orEmpty() }.filter { it in currentTaskIds }
        if (newlyReady.isNotEmpty()) selected = (selected + newlyReady).distinct()
        pendingCatalogueSelection = pendingCatalogueSelection.filterNot { id -> catalogCandidates[id].orEmpty().any { it in currentTaskIds } }
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
    fun supports(candidate: BenchmarkCandidate): Boolean {
        val sources = if (useMatrix && mode != "Voices") matrixLanguages else setOf(source)
        return sources.any { code -> (code in candidate.sourceCodes || !useMatrix && candidate.sourceCodes == setOf("model")) &&
            (mode != "Translation" || (candidate.translation?.supports(code, target)
                ?: (target in candidate.targetCodes && code != target))) }
    }
    val filteredModels = visible.filter { candidate ->
        (modelQuery.isBlank() || listOf(candidate.label, candidate.route, candidate.id).any { it.contains(modelQuery, true) }) &&
            (!compatibleOnly || supports(candidate))
    }
    val task = when (mode) { "Translation" -> BenchmarkTask.TRANSLATION; "Voices" -> BenchmarkTask.VOICE; else -> BenchmarkTask.SPEECH }
    val matrixPlan = remember(candidates, selected, suite, matrixLanguages, task, target, extendedSpeech) {
        suite?.let { bundled -> BenchmarkLanguageMatrix.plan(visible.filter { it.id in selected }, bundled,
            matrixLanguages + if (task == BenchmarkTask.TRANSLATION && target in BenchmarkLanguageMatrix.languages) setOf(target) else emptySet(),
            task, extendedSpeech, translationTarget = target.takeIf { task == BenchmarkTask.TRANSLATION }).let { result -> result.copy(groups = result.groups.filter { group ->
                group.route.source in matrixLanguages && (task != BenchmarkTask.TRANSLATION || group.route.target == target)
            }) } }
    }
    fun runLanguageSweep(candidate: BenchmarkCandidate) {
        val activeSuite = suite ?: return
        val routes = BenchmarkLanguageSweep.routes(candidate, activeSuite)
        if (routes.isEmpty()) return
        if (startBatches(routes.map { route ->
            BenchmarkBatch(listOf(candidate), route.source, route.target, compareAcceleration = compareAcceleration,
                resumeCompleted = resumeCompleted, measuredPasses = measuredPasses, suite = activeSuite,
                cases = activeSuite.selected(route.target.isEmpty(), route.source, route.target, false))
        }, uiText(UiR.string.benchmark_lab_sweep_title))) section = "Results"
    }
    val selectedCompatible = visible.filter { it.id in selected && supports(it) }
    val selectedCount = selectedCompatible.size
    val leaders = remember(results, mode, source, target) {
        BenchmarkComparison.latest(results, source, if (mode == "Translation") target else "",
            task = when (mode) { "Voices" -> "voice"; "Translation" -> "translation"; else -> "speech" })
    }
    fun runComparison() {
        val batches = if (useMatrix && task != BenchmarkTask.VOICE) matrixPlan?.groups.orEmpty().map { group ->
            BenchmarkBatch(group.models, group.route.source, group.route.target, compareAcceleration = compareAcceleration,
                resumeCompleted = resumeCompleted, measuredPasses = measuredPasses, suite = suite, cases = group.cases)
        } else listOf(BenchmarkBatch(selectedCompatible, source, if (mode == "Translation") target else "",
            clip = clip, text = text, referenceText = referenceText.takeIf(String::isNotBlank),
            compareAcceleration = compareAcceleration, resumeCompleted = resumeCompleted, measuredPasses = measuredPasses,
            suite = if (useBuiltInSet && task != BenchmarkTask.VOICE) if (task == BenchmarkTask.SPEECH) activeSpeechSuite else suite else null,
            cases = if (useBuiltInSet && task != BenchmarkTask.VOICE) builtInCases else emptyList()))
        if (startBatches(batches)) section = "Results"
    }
    val matrixActive = useMatrix && task != BenchmarkTask.VOICE
    val durationSuite = if (matrixActive) suite else activeSpeechSuite
    val durationCases = remember(durationSuite, matrixActive, matrixLanguages, builtInCases, extendedSpeech) {
        if (matrixActive) matrixLanguages.sorted().flatMap { code ->
            durationSuite?.selected(true, code, "", full = extendedSpeech).orEmpty()
        } else builtInCases
    }
    LaunchedEffect(task, matrixActive, useBuiltInSet, durationSuite, durationCases, clip?.sha256) {
        audioDurations = null
        if (task == BenchmarkTask.SPEECH) {
            try {
                audioDurations = withContext(Dispatchers.IO) {
                    if (!matrixActive && !useBuiltInSet) clip?.let { BenchmarkAudioDurations(listOf(it.durationMs), emptyList()) }
                    else durationSuite?.let { selectedSuite ->
                        val durations = durationCases.map { sample -> sample to selectedSuite.audio(context, sample).durationMs }
                        BenchmarkAudioDurations(durations.filter { it.first.silenceMs == 0 }.map { it.second },
                            durations.filter { it.first.silenceMs > 0 }.map { it.second })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.toString() }
        }
    }
    val readiness = benchmarkReadinessIssue(task, selectedCount, !discovered, busy || recordingStop != null,
        gateOwner, running, matrixActive || useBuiltInSet,
        if (matrixActive) matrixPlan?.groups?.sumOf { it.cases.size } ?: 0 else builtInCases.size,
        !matrixActive && useSaudiSet, clip != null, text.isNotBlank(),
        if (matrixActive) matrixPlan?.groups?.firstOrNull()?.route?.source ?: matrixLanguages.firstOrNull().orEmpty() else source, target)
    val languageName = uiText.languageLabel(LanguageCatalog.option(source))
    val inputSummary = when {
        matrixActive -> uiText.bench("Bundled", "المجموعة المرفقة", "内置") + " · " + matrixLanguages.joinToString(" / ") { it.uppercase(Locale.ROOT) } +
            if (task == BenchmarkTask.TRANSLATION) " → ${target.uppercase(Locale.ROOT)}" else ""
        task == BenchmarkTask.VOICE -> languageName + " · " + uiText(UiR.string.benchmark_lab_voice_text)
        !useBuiltInSet -> languageName + " · " + clipName.ifBlank { uiText(UiR.string.benchmark_custom_input) }
        task == BenchmarkTask.SPEECH && source == "ar" && useSaudiSet -> languageName + " · " + uiText(UiR.string.benchmark_saudi_set) + " · ${saudiSuite?.cases?.size ?: 0}"
        else -> languageName + " · " + uiText(UiR.string.benchmark_builtin_set)
    }
    fun editInput() { section = "Setup"; scope.launch { listState.scrollToItem(0) } }
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
                val targetStore = if (pendingSaudiImport) saudiDraftStore else customAudioStore
                val saved = withContext(Dispatchers.IO) { targetStore.save(loaded.second, loaded.first) }
                if (pendingSaudiImport) { saudiClipName = saved.label; saudiClip = saved.clip }
                else { clipName = saved.label; clip = saved.clip; restoredAudio = true }
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
                val recorded = SaudiBenchmarkRecorder.capture(stop)
                val saved = withContext(Dispatchers.IO) { saudiDraftStore.save(recorded, uiText(UiR.string.benchmark_saudi_recording)) }
                saudiClip = saved.clip; saudiClipName = saved.label
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
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text(uiText(UiR.string.benchmark_lab_title), style = MaterialTheme.typography.headlineMedium)
            Text(uiText(UiR.string.benchmark_lab_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Setup", "Models", "Results").forEach { destination ->
                    FilterChip(section == destination, { section = destination; scope.launch { listState.scrollToItem(0) } }, label = {
                        Text(when (destination) {
                            "Models" -> uiText(UiR.string.benchmark_lab_models_count, selectedCount)
                            "Results" -> uiText(UiR.string.benchmark_lab_results_count, results.size)
                            else -> uiText(UiR.string.benchmark_lab_setup)
                        })
                    })
                }
            }
            if (discovering || catalogueRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(uiText.bench("Refreshing installed models… Your input and choices are kept.",
                    "جارٍ تحديث النماذج المثبتة… يتم الاحتفاظ بمدخلاتك واختياراتك.", "正在刷新已安装模型…保留输入和选择。"), style = MaterialTheme.typography.bodySmall)
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
        if (discoveryIssues.isNotEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiText(UiR.string.benchmark_discovery_summary, discoveryIssues.size), style = MaterialTheme.typography.titleSmall)
                    Text(uiText(UiR.string.benchmark_discovery_continue), style = MaterialTheme.typography.bodySmall)
                    TextButton({ showDiscoveryIssues = true }) { Text(uiText(UiR.string.benchmark_discovery_review)) }
                }
            }
        }
        if (section == "Setup") item {
            Text(uiText(UiR.string.benchmark_lab_input_heading), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (matrixActive) Text(uiText.bench("Sample languages", "لغات العينات", "样本语言") + ": " +
                matrixLanguages.joinToString(" / ") { uiText.languageLabel(LanguageCatalog.option(it)) }, style = MaterialTheme.typography.bodyMedium)
            else OutlinedButton({ picker = "source" }, enabled = !running && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_1_s_language_2_s_a760d, if (mode == "Speech") uiText(UiR.string.language_spoken) else if (mode == "Voices") uiText(UiR.string.benchmark_lab_voice_language) else uiText(UiR.string.language_source), uiText.languageLabel(LanguageCatalog.option(source))))
            }
            if (mode == "Translation") OutlinedButton({ picker = "target" }, enabled = !running && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(uiText(UiR.string.ui_translate_to_1_s_f70dc, uiText.languageLabel(LanguageCatalog.option(target)))) }
            Text(if (mode == "Speech") uiText(UiR.string.ui_forced_language_is_used_where_supported_vosk_always_uses_its_mode_86f53)
                else if (mode == "Voices") uiText(UiR.string.benchmark_lab_voice_language_hint)
                else uiText(UiR.string.ui_compare_the_same_corrected_source_text_separately_from_recognitio_7e2d4), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (section == "Setup" && mode != "Voices") item {
            Text(uiText.bench("Reference inputs", "مدخلات المقارنة المرجعية", "参考输入"), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!useMatrix, { useMatrix = false }, enabled = !running && !busy,
                    label = { Text(uiText.bench("One language / my recording", "لغة واحدة / تسجيلي", "单个语言 / 我的录音")) })
                FilterChip(useMatrix, { useMatrix = true }, enabled = !running && !busy,
                    label = { Text(uiText.bench("Bundled language comparison", "مقارنة اللغات المرفقة", "内置多语言对比")) })
            }
            if (useMatrix) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BenchmarkLanguageMatrix.languages.forEach { code ->
                        FilterChip(code in matrixLanguages, {
                            matrixLanguageCodes = (if (code in matrixLanguages) matrixLanguages - code else matrixLanguages + code).joinToString(",")
                        }, enabled = !running && !busy, label = { Text(uiText.languageLabel(LanguageCatalog.option(code))) })
                    }
                }
                Text(uiText.bench("Real bundled audio and publisher transcripts. Each model runs only its advertised languages; results stay separate per language.",
                    "صوت حقيقي ونصوص الناشر مرفقة. يُختبر كل نموذج في لغاته المعلنة فقط، وتبقى النتائج منفصلة لكل لغة.",
                    "内置真实音频和发布者文本。仅测试模型声明支持的语言，每种语言单独记录结果。"), style = MaterialTheme.typography.bodySmall)
                Text("${matrixPlan?.comparisonCount ?: 0} · " + uiText.bench("model/language comparisons", "مقارنة نموذج/لغة", "模型/语言对比"), style = MaterialTheme.typography.labelLarge)
                matrixPlan?.skipped?.takeIf { it.isNotEmpty() }?.let { reasons ->
                    Text(uiText.bench("Unsupported routes are skipped", "تُتخطى المسارات غير المدعومة", "跳过不支持的语言方向") + " · ${reasons.size}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton({ showSamples = true }, enabled = suite != null && !running) {
                Text(uiText.bench("Preview audio, source text and references", "عرض الصوت والنص الأصلي والمراجع", "预览音频、源文本和参考文本"))
            }
            if (mode == "Speech") {
                Text(uiText.bench("Recognition language control", "التحكم في لغة التعرّف", "识别语言控制"), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BenchmarkLanguageMode.entries.forEach { choice ->
                        FilterChip(languageMode == choice, { languageMode = choice }, enabled = !running && !busy, label = { Text(when(choice) {
                            BenchmarkLanguageMode.MATCH_INPUT -> uiText.bench("Match sample language", "مطابقة لغة العينة", "匹配样本语言")
                            BenchmarkLanguageMode.AUTOMATIC -> uiText.bench("Automatic", "تلقائي", "自动")
                            BenchmarkLanguageMode.BOTH -> uiText.bench("Compare both", "مقارنة الاثنين", "对比两种模式")
                        }) })
                    }
                }
                Text(uiText.bench("A language hint is applied where supported. Fixed-language and automatic-only models are labelled accordingly and run once.",
                    "يُمرر تلميح اللغة حيث يدعمه المحرك. تُوضح النماذج ذات اللغة الثابتة أو التلقائية فقط وتُختبر مرة واحدة.",
                    "支持语言提示的模型会应用提示。固定语言或仅自动识别的模型会正确标注且仅运行一次。"), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (section == "Setup") item {
            Text(uiText.bench("Time limit per model", "المهلة لكل نموذج", "每个模型的时间限制"), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                benchmarkAttemptTimeoutPresets.forEach { seconds ->
                    FilterChip(attemptTimeoutSeconds == seconds, { attemptTimeoutSeconds = seconds },
                        enabled = !running && !busy, modifier = Modifier.testTag("benchmark-time-limit-$seconds"),
                        label = { Text(uiText.bench("$seconds seconds", "$seconds ثانية", "$seconds 秒")) })
                }
            }
            Text(uiText.bench(
                "The limit covers loading, every sample, all $measuredPasses passes and cleanup for one model/backend/language attempt. Slow models are disqualified and skipped for the rest of this queue; no quality score is awarded.",
                "تشمل المهلة التحميل وجميع العينات والمرورات البالغ عددها $measuredPasses والتنظيف لمحاولة نموذج/مسار/لغة واحدة. تُستبعد النماذج البطيئة من بقية الطابور دون منحها درجة جودة.",
                "时限包含单个模型/后端/语言的加载、所有样本、全部 $measuredPasses 轮及清理。超时模型将被取消资格并跳过后续队列，不授予质量分数。"),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("benchmark-time-limit-description"))
            audioDurations?.takeIf { it.recordings.isNotEmpty() }?.let { durations ->
                val range = if (durations.shortestMs == durations.longestMs) number(durations.shortestMs / 1000.0)
                    else "${number(durations.shortestMs / 1000.0)}–${number(durations.longestMs / 1000.0)}"
                Text(uiText.bench(
                    "${durations.recordings.size} recordings · $range s each · ${number(durations.totalMs / 1000.0)} s ${if (matrixActive) "across selected languages (tested separately)" else "input per pass"}${if (durations.silenceChecks.isNotEmpty()) " (includes ${durations.silenceChecks.size} silence checks)" else ""}. $measuredPasses passes: first + ${measuredPasses - 1} warm.",
                    "${durations.recordings.size} تسجيلات · $range ث لكل تسجيل · ${number(durations.totalMs / 1000.0)} ث ${if (matrixActive) "لمجموع اللغات المختارة (تُختبر كل لغة منفصلة)" else "من المدخلات لكل مرور"}${if (durations.silenceChecks.isNotEmpty()) " (تشمل ${durations.silenceChecks.size} فحوص صمت)" else ""}. $measuredPasses مرورات: الأولى + ${measuredPasses - 1} بعد الإحماء.",
                    "${durations.recordings.size} 段录音 · 每段 $range 秒 · ${number(durations.totalMs / 1000.0)} 秒${if (matrixActive) "所选语言的总输入（每种语言单独测试）" else "每轮输入"}${if (durations.silenceChecks.isNotEmpty()) "（包括 ${durations.silenceChecks.size} 次静音检查）" else ""}。共 $measuredPasses 轮：首次 + ${measuredPasses - 1} 次预热后测量。"),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("benchmark-audio-duration"))
            }
            Spacer(Modifier.height(12.dp))
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
                showLanguages = matrixActive, onExport = { rows, format ->
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
        if (section == "Setup" && mode != "Voices" && !useMatrix) item {
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
                            FilterChip(saudiPrompt == index, { saudiPrompt = index; saudiReferenceText = prompt },
                                enabled = !running && recordingStop == null && !busy,
                                label = { Text("${index + 1}") },
                                modifier = Modifier.semantics { contentDescription = "${index + 1}. $prompt" })
                        }
                    }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Text(SaudiBenchmarkPack.prompts[saudiPrompt], Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleLarge)
                    }
                    if (saudiReferenceText.isBlank()) LaunchedEffect(saudiPrompt) { saudiReferenceText = SaudiBenchmarkPack.prompts[saudiPrompt] }
                    if (recordingStop != null) Button({ recordingStop?.set(true) }, Modifier.fillMaxWidth()) {
                        Text(uiText(UiR.string.benchmark_stop_recording))
                    } else Button({
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            startSaudiRecording()
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, Modifier.fillMaxWidth(), enabled = !running && !busy &&
                        (saudiSuite?.cases?.size ?: 0) < SaudiBenchmarkPack.MAX_CLIPS) {
                        Text(uiText(UiR.string.benchmark_record_saudi)) }
                    OutlinedButton({ pendingSaudiImport = true; openAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
                        Modifier.fillMaxWidth(), enabled = !running && !busy && recordingStop == null &&
                            (saudiSuite?.cases?.size ?: 0) < SaudiBenchmarkPack.MAX_CLIPS) {
                        Text(uiText(UiR.string.benchmark_import_saudi))
                    }
                    saudiClip?.let { chosen ->
                        Text("$saudiClipName · ${number(chosen.durationMs / 1000.0)} s", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(saudiReferenceText, { if (it.length <= 500) saudiReferenceText = it },
                            label = { Text(uiText(UiR.string.benchmark_reference_transcript)) },
                            supportingText = { Text(uiText(UiR.string.benchmark_saudi_reference_hint)) },
                            modifier = Modifier.fillMaxWidth(), enabled = !running && !busy && recordingStop == null)
                        Button({ scope.launch {
                            busy = true; error = null
                            try {
                                saudiSuite = withContext(Dispatchers.IO) { saudiStore.save(chosen, saudiReferenceText) }
                                saudiClip = null; saudiClipName = ""
                                withContext(Dispatchers.IO) { saudiDraftStore.clear() }
                                saudiPrompt = ((saudiSuite?.cases?.size ?: 0) % SaudiBenchmarkPack.MAX_CLIPS)
                                saudiReferenceText = SaudiBenchmarkPack.prompts[saudiPrompt]
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                            finally { busy = false }
                        } }, enabled = !running && !busy && saudiReferenceText.isNotBlank()) {
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
                    OutlinedButton({ pendingSaudiImport = false; openAudio.launch(arrayOf("audio/*", "application/octet-stream")) }, enabled = !running && !busy,
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
            if (mode != "Voices") TextButton({ matrixLanguageCodes = BenchmarkLanguageMatrix.defaultLanguages.joinToString(","); useMatrix = true; editInput() }, enabled = !running && !busy) {
                Text(uiText.bench("Use bundled English / Russian / Chinese tests", "استخدام الاختبارات المرفقة بالإنجليزية والروسية والصينية", "使用内置英语 / 俄语 / 中文测试"))
            }
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
            val sweepRoutes = suite?.let { BenchmarkLanguageSweep.routes(candidate, it) }.orEmpty()
            val sweepResults = suite?.let { activeSuite -> sweepRoutes.mapNotNull { route ->
                val inputSuite = activeSuite
                results.firstOrNull { result -> result.identity.substringBefore('@').substringBefore("#language=") == candidate.id && result.source == route.source &&
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
    error?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 4) }
    BenchmarkRunFooter(readiness, selectedCount, measuredPasses, inputSummary + " · " +
        uiText.bench("${attemptTimeoutSeconds}s/model limit", "مهلة ${attemptTimeoutSeconds}ث/نموذج", "每个模型最多 ${attemptTimeoutSeconds} 秒"),
        compareAcceleration && task != BenchmarkTask.VOICE, running, runState.stopping, status, gateOwner,
        onRun = ::runComparison, onStop = ::stop,
        onFix = { fix -> when (fix) {
            BenchmarkReadinessFix.INPUT -> editInput()
            BenchmarkReadinessFix.MODELS -> { section = "Models"; scope.launch { listState.scrollToItem(0) } }
            BenchmarkReadinessFix.STOP_ACTIVITY -> if (gateOwner == "Captions") com.sal7one.transiber.caption.stopHearthSessions(context) else { showSessionHelp = true }
            BenchmarkReadinessFix.NONE -> Unit
        } }, onEditInput = ::editInput,
        onUseBundledSpeech = { useBuiltInSet = true; useSaudiSet = false; useMatrix = false; source = "ar" })
    }
    if (showSamples && suite != null) BenchmarkSamplePreview(checkNotNull(suite),
        if (mode == "Speech") BenchmarkTask.SPEECH else BenchmarkTask.TRANSLATION, onDismiss = { showSamples = false })
    if (showSessionHelp) AlertDialog(onDismissRequest = { showSessionHelp = false },
        title = { Text(gateOwner.orEmpty()) },
        text = { Text(uiText.bench("Finish or stop this active session before starting a benchmark. Its models remain owned until cleanup finishes.",
            "أنهِ الجلسة النشطة أو أوقفها قبل بدء الاختبار. تبقى النماذج محجوزة حتى اكتمال التنظيف.",
            "请先结束或停止当前会话，再启动基准测试。清理完成前模型仍由该会话占用。")) },
        confirmButton = { TextButton({ showSessionHelp = false }) { Text(uiText(UiR.string.benchmark_lab_close)) } })
    if (showDiscoveryIssues) AlertDialog(onDismissRequest = { showDiscoveryIssues = false },
        title = { Text(uiText(UiR.string.benchmark_discovery_review)) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(discoveryIssues) { issue -> SelectionContainer { Text(issue) } }
        } },
        confirmButton = { TextButton({ showDiscoveryIssues = false }) { Text(uiText(UiR.string.benchmark_lab_close)) } },
        dismissButton = { TextButton({ showDiscoveryIssues = false; onModels(mode) }) { Text(uiText(UiR.string.benchmark_lab_import_details)) } })
    if (catalogueOpen) BenchmarkCatalogueDialog(catalogue, source, target, catalogCandidates.keys, downloadRecords,
        catalogueSelection, { catalogueSelection = it }, freeBytes, queueing, queueStatus, queueState.errors, ::queueModels,
        onPause = { records -> scope.launch {
            try { withContext(Dispatchers.IO) { records.forEach { downloads.pause(it.id) } }; downloadRecords = withContext(Dispatchers.IO) { downloads.list() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        } }, onDownloads = onDownloads, onModels = { onModels(mode) }, onDismiss = { catalogueOpen = false },
        sourceLanguages = if (matrixActive) matrixLanguages else setOf(source))
    picker?.let { which ->
        val pickerSources = if (matrixActive) matrixLanguages else setOf(source)
        val candidatesForPicker = if (which == "target") visible.filter { candidate ->
            pickerSources.any { it in candidate.sourceCodes || candidate.sourceCodes == setOf("model") }
        } else visible
        val codes = (if (which == "target") candidatesForPicker.flatMap { candidate ->
            candidate.targetCodes.filter { code -> pickerSources.any { from ->
                candidate.translation?.supports(from, code) ?: (code != from && code in candidate.targetCodes)
            } }
        } + TranslationCatalog.models.flatMap { model -> model.targetLanguages.filter { code -> pickerSources.any { model.supports(it, code) } } } +
            MarianPackage.pairs.filter { it.source in pickerSources }.map { it.target } +
            (if (PlatformTranslation.available) TranslationOptions.mlKitCodes.filter { code -> pickerSources.any { it != code } } else emptyList())
        else candidatesForPicker.flatMap { it.sourceCodes } + if (mode == "Voices")
            com.sal7one.transiber.voice.LocalVoiceFamilies.providers.flatMap { it.languages } else if (mode == "Speech")
            SpeechArtifactCatalog.all.flatMap { it.languages } else
            TranslationCatalog.models.flatMap { it.sourceLanguages } + MarianPackage.pairs.map { it.source } +
                (if (PlatformTranslation.available) TranslationOptions.mlKitCodes else emptySet()))
            .toSet().let { it - setOf("auto", "model") }
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
