package com.sal7one.transiber.benchmark

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

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
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionLanguageChoices
import com.sal7one.transiber.caption.LanguagePickerContent
import com.sal7one.transiber.downloads.DownloadSpec
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

/** Explicit finite local-only experiments. Leaving/backgrounding this page cancels safely. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalBenchmarkScreen(onModels: (String) -> Unit = {}, onDownloads: () -> Unit = {}) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val runner = remember { LocalBenchmarkRunner(context.applicationContext) }
    val store = remember { BenchmarkResults(context.applicationContext) }
    val downloads = remember { FileDownloads(context.applicationContext) }
    var candidates by remember { mutableStateOf<List<BenchmarkCandidate>>(emptyList()) }
    var results by remember { mutableStateOf<List<BenchmarkResult>>(emptyList()) }
    var suite by remember { mutableStateOf<BenchmarkSuite?>(null) }
    var clip by remember { mutableStateOf<BenchmarkAudio.Clip?>(null) }
    var clipName by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("Speech") }
    var preset by rememberSaveable { mutableStateOf(BenchmarkPreset.SPEED) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var source by rememberSaveable { mutableStateOf("en") }
    var target by rememberSaveable { mutableStateOf("ar") }
    var text by rememberSaveable { mutableStateOf("") }
    var referenceText by rememberSaveable { mutableStateOf("") }
    var useBuiltInSet by rememberSaveable { mutableStateOf(true) }
    var historyForPair by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var downloadMessage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val gateOwner by LocalWorkGate.owner.collectAsState()
    fun stop() { runner.cancel(); job?.cancel(); if (running) status = uiText(UiR.string.ui_stopping_after_the_current_native_operation_c83bb) }
    val currentStop by rememberUpdatedState(newValue = { stop() })
    val currentRunning by rememberUpdatedState(running)
    DisposableEffect(lifecycle, runner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentStop()
            if (event == Lifecycle.Event.ON_RESUME && !currentRunning) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); currentStop() }
    }
    LaunchedEffect(refresh) {
        try {
            busy = true
            suite = withContext(Dispatchers.IO) { BenchmarkSuite.bundled(context) }
            candidates = runner.candidates()
            results = withContext(Dispatchers.IO) { store.load() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
        finally { busy = false }
    }
    val visible = candidates.filter { it.targetCodes.isNotEmpty() == (mode == "Translation") }
    val plan = remember(source, target, preset) { BenchmarkPresets.forPair(source, target).first { it.preset == preset } }
    fun installed(model: SuggestedModel?): BenchmarkCandidate? = when (model?.kind) {
        "speech" -> candidates.firstOrNull { it.profile?.id == model.id && source in it.sourceCodes }
        else -> candidates.firstOrNull { it.id == model?.id && source in it.sourceCodes && target in it.targetCodes &&
            (it.translation?.supports(source, target) ?: true) }
    }
    fun queue(model: SuggestedModel) {
        scope.launch {
            busy = true; error = null
            try {
                withContext(Dispatchers.IO) {
                    when (model.kind) {
                        "speech" -> checkNotNull(SpeechArtifactCatalog.find(model.id)).let {
                            downloads.enqueue(DownloadSpec.parse(it.url, it.fileName), true, it.id)
                        }
                        "gguf" -> checkNotNull(TranslationCatalog.models.firstOrNull { it.id == model.id }).let {
                            downloads.enqueue(DownloadSpec.parse(it.url, it.fileName), true, it.id)
                        }
                        "marian" -> MarianPackage.enqueue(context, downloads, checkNotNull(MarianPackage.find(model.id)))
                        "cascade" -> MarianCascade.enqueue(context, downloads, checkNotNull(MarianCascade.find(model.id)))
                        else -> error("${model.label} is configured from Models")
                    }
                }
                downloadMessage = uiText(UiR.string.benchmark_download_queued, model.label)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    val builtInCases = remember(suite, mode, source, target) {
        suite?.selected(mode == "Speech", source, target, full = false).orEmpty()
    }
    fun supports(candidate: BenchmarkCandidate) = (source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")) &&
        (mode == "Speech" || (candidate.translation?.supports(source, target)
            ?: (target in candidate.targetCodes && source != target)))
    val availableIds = visible.filter(::supports).map { it.id }
    val displayedResults = results.filter { !historyForPair ||
        (it.target.isNotBlank() == (mode == "Translation") && it.source == source &&
            (mode == "Speech" || it.target == target)) }
    LaunchedEffect(mode, source, target, candidates) {
        // Empty is an intentional choice (Deselect all), including when a
        // newly installed model refreshes the list. Do not silently run the
        // first two models instead of the one the user just chose.
        selected = selected.filter { it in availableIds }
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
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = store.export()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) }
                        ?: kotlin.error(uiText(UiR.string.ui_cannot_write_benchmark_results_a2d0d))
                }
                status = uiText(UiR.string.ui_results_exported_including_transcripts_8df04)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(uiText(UiR.string.ui_compare_local_models_62748), style = MaterialTheme.typography.headlineSmall)
            Text(uiText(UiR.string.ui_use_the_same_recording_or_corrected_text_for_each_model_three_pas_a5005),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Speech", "Translation").forEach { label ->
                    FilterChip(mode == label, { mode = label }, enabled = !running && !busy, label = { Text(uiText.modelSection(label)) })
                }
            }
        }
        item {
            OutlinedButton({ picker = "source" }, enabled = !running && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_1_s_language_2_s_a760d, if (mode == "Speech") uiText(UiR.string.language_spoken) else uiText(UiR.string.language_source), uiText.languageLabel(LanguageCatalog.option(source))))
            }
            OutlinedButton({ picker = "target" }, enabled = !running && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(uiText(UiR.string.ui_translate_to_1_s_f70dc, uiText.languageLabel(LanguageCatalog.option(target)))) }
            Text(if (mode == "Speech") uiText(UiR.string.ui_forced_language_is_used_where_supported_vosk_always_uses_its_mode_86f53)
                else uiText(UiR.string.ui_compare_the_same_corrected_source_text_separately_from_recognitio_7e2d4), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        item {
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
                            Text(if (ready != null) uiText(UiR.string.benchmark_installed_ready)
                                else if (model.kind == "mlkit") uiText(UiR.string.benchmark_mlkit_packs_needed)
                                else uiText(UiR.string.benchmark_download_size, model.bytes?.div(1_048_576)?.toString() ?: "—"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ready != null) Button(onClick = {
                                    mode = stage; selected = listOf(ready.id); useBuiltInSet = true
                                }, enabled = !busy && !running,
                                    modifier = Modifier.semantics { contentDescription = "${uiText(UiR.string.benchmark_test_this)}: ${model.label}" }) {
                                    Text(uiText(UiR.string.benchmark_test_this))
                                }
                                else if (ByokPolicy.FEATURE_BYOK && model.kind != "mlkit")
                                    Button(onClick = { queue(model) }, enabled = !busy && !running,
                                        modifier = Modifier.semantics { contentDescription = "${uiText(UiR.string.benchmark_download_model)}: ${model.label}" }) {
                                        Text(uiText(UiR.string.benchmark_download_model))
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
                    val speechSamples = suite?.selected(true, source, target, full = false).orEmpty()
                    val translationSamples = suite?.selected(false, source, target, full = false).orEmpty()
                    if (speechModel != null && translationModel != null && speechSamples.isNotEmpty() && translationSamples.isNotEmpty())
                        Button(onClick = {
                            running = true; error = null
                            job = scope.launch {
                                try {
                                    val failures = mutableListOf<String>()
                                    val activeSuite = checkNotNull(suite)
                                    suspend fun inputs(samples: List<BenchmarkCase>) = withContext(Dispatchers.IO) {
                                        samples.map { sample -> BenchmarkInput(sample.id, text = sample.text,
                                            reference = sample.reference,
                                            clip = if (sample.speech) activeSuite.audio(context, sample) else null,
                                            silence = sample.silenceMs > 0) }
                                    }
                                    runner.run(listOf(speechModel), null, "", null, source, target,
                                        benchmarkInputs = inputs(speechSamples), progress = { status = it },
                                        onResult = {
                                            results = (listOf(it) + results).take(BenchmarkResults.MAX_HISTORY)
                                            it.error?.let { cause -> failures += "${it.model}: $cause" }
                                        })
                                    runner.run(listOf(translationModel), null, "", null, source, target,
                                        benchmarkInputs = inputs(translationSamples), progress = { status = it },
                                        onResult = {
                                            results = (listOf(it) + results).take(BenchmarkResults.MAX_HISTORY)
                                            it.error?.let { cause -> failures += "${it.model}: $cause" }
                                        })
                                    if (failures.isEmpty()) status = uiText(UiR.string.benchmark_pair_complete)
                                    else { status = ""; error = failures.joinToString("\n") }
                                } catch (e: CancellationException) { status = uiText(UiR.string.ui_comparison_stopped_completed_results_were_saved_7c2db); throw e }
                                catch (e: Exception) { error = e.message ?: e.toString() }
                                finally { running = false }
                            }
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
                    if (downloadMessage.isNotBlank()) Text(downloadMessage, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(useBuiltInSet, { useBuiltInSet = true }, enabled = !running && !busy,
                    label = { Text(uiText(UiR.string.benchmark_builtin_set)) })
                FilterChip(!useBuiltInSet, { useBuiltInSet = false }, enabled = !running && !busy,
                    label = { Text(uiText(UiR.string.benchmark_custom_input)) })
            }
            if (useBuiltInSet) {
                Text(uiText(UiR.string.benchmark_suite_summary), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
                Text(uiText(UiR.string.benchmark_suite_attribution), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (builtInCases.isEmpty()) {
                    Text(uiText(UiR.string.benchmark_suite_pair_unavailable, source, target),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp))
                    TextButton({ useBuiltInSet = false }, enabled = !running && !busy) {
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
        item {
            Text(uiText(UiR.string.ui_installed_models_c45cb), style = MaterialTheme.typography.titleMedium)
            if (visible.isEmpty()) Text(uiText(UiR.string.ui_no_models_installed_for_this_comparison_download_or_import_one_in_dc1e2), Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onModels(mode) }, enabled = !running && !busy) { Text(uiText(UiR.string.ui_open_models_53f82)) }
                TextButton({ refresh++ }, enabled = !running && !busy) { Text(uiText(UiR.string.ui_refresh_56e3b)) }
                if (availableIds.isNotEmpty()) TextButton({ selected = if (selected.isEmpty()) availableIds.take(12) else emptyList() },
                    enabled = !running && !busy) { Text(if (selected.isEmpty()) uiText(UiR.string.ui_select_compatible_models_ee8e9) else uiText(UiR.string.ui_deselect_all_85cce)) }
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
                    Text(if (supports(candidate)) candidate.route else uiText(UiR.string.ui_not_compatible_with_this_language_selection_aa8b5),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            if (gateOwner != null && gateOwner != "Benchmark") Text(uiText(UiR.string.ui_stop_1_s_before_comparing_models_fc4d7, gateOwner), color = MaterialTheme.colorScheme.error)
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = uiText(UiR.string.ui_benchmark_in_progress_c6417) })
                Button(::stop, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(uiText(UiR.string.ui_stop_comparison_7826e)) }
            } else Button({
                val chosen = visible.filter { it.id in selected && supports(it) }
                running = true; error = null
                job = scope.launch {
                    try {
                        val failures = mutableListOf<String>()
                        val inputs = if (useBuiltInSet) withContext(Dispatchers.IO) {
                            val activeSuite = checkNotNull(suite) { "Built-in benchmark data is unavailable" }
                            builtInCases.map { sample ->
                                BenchmarkInput(sample.id, text = sample.text, reference = sample.reference,
                                    clip = if (sample.speech) activeSuite.audio(context, sample) else null,
                                    silence = sample.silenceMs > 0)
                            }
                        } else emptyList()
                        runner.run(chosen, clip, text, referenceText.takeIf(String::isNotBlank), source, target,
                            benchmarkInputs = inputs, progress = { status = it }, onResult = { result ->
                            results = (listOf(result) + results).take(BenchmarkResults.MAX_HISTORY)
                            result.error?.let { cause -> failures += "${result.model}: $cause" }
                        })
                        if (failures.isEmpty()) status = uiText(UiR.string.ui_comparison_complete_review_accuracy_as_well_as_speed_16b3e)
                        else { status = ""; error = failures.joinToString("\n") }
                    } catch (e: CancellationException) { status = uiText(UiR.string.ui_comparison_stopped_completed_results_were_saved_7c2db); throw e }
                    catch (e: Exception) { error = e.message ?: e.toString() }
                    finally { running = false }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy && gateOwner == null && selected.isNotEmpty() &&
                (if (useBuiltInSet) builtInCases.isNotEmpty() else if (mode == "Speech") clip != null else text.isNotBlank() && source != target)) {
                Text(uiText(UiR.string.ui_compare_1_s_models_1916c, selected.size))
            }
            if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            error?.let { SelectionContainer { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) } }
        }
        item {
            Text(uiText(UiR.string.ui_speed_is_measured_with_accelerated_replay_not_microphone_to_capti_fcf89),
                style = MaterialTheme.typography.bodySmall)
        }
        if (results.isNotEmpty()) {
            item {
                val leaders = BenchmarkComparison.latest(results, source, if (mode == "Speech") "" else target)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(uiText(UiR.string.benchmark_measured_title), style = MaterialTheme.typography.titleMedium)
                        if (leaders == null || leaders.candidates.size < 2)
                            Text(uiText(UiR.string.benchmark_measured_need_two), style = MaterialTheme.typography.bodySmall)
                        else {
                            leaders.fastest?.let { Text(uiText(UiR.string.benchmark_measured_fastest,
                                it.model, number(BenchmarkMetrics.median(it.computeMs.drop(1)) / 1000))) }
                            leaders.balanced?.let { Text(uiText(UiR.string.benchmark_measured_balanced, it.model)) }
                            if (leaders.balanced == null && leaders.mostAccurate != null)
                                Text(uiText(UiR.string.benchmark_measured_need_three), style = MaterialTheme.typography.bodySmall)
                            leaders.mostAccurate?.let { Text(uiText(UiR.string.benchmark_measured_accurate, it.model)) }
                            if (leaders.mostAccurate == null) Text(uiText(UiR.string.benchmark_measured_no_reference), style = MaterialTheme.typography.bodySmall)
                            Text(uiText(UiR.string.benchmark_measured_scope), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text(uiText(UiR.string.ui_saved_results_5e7cc), style = MaterialTheme.typography.titleLarge)
                Text(uiText(UiR.string.ui_stored_only_on_this_device_export_includes_recognized_and_transla_f23f5), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        val name = "hearth-benchmarks-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                        export.launch(name)
                    }, enabled = !running) { Text(uiText(UiR.string.ui_export_results_fc7d7)) }
                    TextButton({ scope.launch {
                        try { withContext(Dispatchers.IO) { store.clear() }; results = emptyList() }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                    } }, enabled = !running) { Text(uiText(UiR.string.ui_clear_history_53b51)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!historyForPair, { historyForPair = false }, label = { Text(uiText(UiR.string.benchmark_all_results)) })
                    FilterChip(historyForPair, { historyForPair = true }, label = {
                        Text(uiText(if (mode == "Speech") UiR.string.benchmark_this_language else UiR.string.benchmark_this_language_pair))
                    })
                }
            }
            if (displayedResults.isEmpty()) item {
                Text(uiText(UiR.string.benchmark_no_results_for_language), style = MaterialTheme.typography.bodyMedium)
            }
            items(displayedResults, key = { it.runId + it.identity }) { result -> BenchmarkResultCard(result) }
        }
    }
    picker?.let { which ->
        val candidatesForPicker = if (which == "target") visible.filter { source in it.sourceCodes || it.sourceCodes == setOf("model") } else visible
        val codes = (if (which == "target") candidatesForPicker.flatMap { candidate ->
            candidate.targetCodes.filter { code -> candidate.translation?.supports(source, code)
                ?: (code != source && code in candidate.targetCodes) }
        } + TranslationCatalog.models.flatMap { it.targetLanguages.filter { code -> it.supports(source, code) } } +
            MarianPackage.pairs.filter { it.source == source }.map { it.target } +
            (if (PlatformTranslation.available) TranslationOptions.mlKitCodes.filter { it != source } else emptyList())
        else candidatesForPicker.flatMap { it.sourceCodes } + if (mode == "Speech")
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
@Composable
private fun BenchmarkResultCard(result: BenchmarkResult) {
    val uiText = rememberUiText()

    var details by rememberSaveable(result.runId, result.identity) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(result.model, style = MaterialTheme.typography.titleMedium)
            Text("${result.source}${if (result.target.isBlank()) "" else " → ${result.target}"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.timestamp))}",
                style = MaterialTheme.typography.bodySmall)
            result.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (result.target.isBlank() && result.characterErrorRate != null) {
                val errorLabel = if (result.source == "zh" || result.wordErrorRate == null)
                    uiText(UiR.string.benchmark_character_error_only, number(result.characterErrorRate * 100.0))
                else uiText(UiR.string.benchmark_error_rates,
                    number(result.wordErrorRate * 100.0), number(result.characterErrorRate * 100.0))
                Text(errorLabel, style = MaterialTheme.typography.bodyMedium)
            } else if (result.referenceText == null) {
                Text(uiText(UiR.string.benchmark_no_reference), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val silenceChecks = result.samples.filter { it.silence }
            if (result.target.isBlank() && silenceChecks.isNotEmpty()) {
                val falsePositives = silenceChecks.count { it.text.isNotBlank() }
                Text(uiText(UiR.string.benchmark_silence_false_positives, falsePositives, silenceChecks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (falsePositives == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            result.translationChrf?.let { Text(uiText(UiR.string.benchmark_translation_chrf, number(it * 100.0)),
                style = MaterialTheme.typography.bodyMedium) }
            Text(uiText(UiR.string.ui_load_verify_1_s_s_05a13, number(result.loadMs / 1000)))
            if (result.computeMs.isNotEmpty()) Text(uiText(UiR.string.ui_first_inference_1_s_s_14e86, number(result.computeMs.first() / 1000)))
            if (result.computeMs.size >= 3) {
                val warm = BenchmarkMetrics.median(result.computeMs.drop(1))
                Text(uiText(UiR.string.ui_warm_median_1_s_s_0f226, number(warm / 1000)))
                if (result.audioMs > 0) Text(uiText(UiR.string.ui_real_time_factor_1_s_below_1_keeps_ahead_in_replay_10505, number(BenchmarkMetrics.realTimeFactor(warm, result.audioMs))))
            }
            val output = result.texts.lastOrNull()
            if (output != null) SelectionContainer { Text(output.ifBlank { uiText(UiR.string.ui_no_speech_recognized_a_fast_empty_result_is_not_a_quality_win_c93af) }) }
            TextButton({ details = !details }) { Text(if (details) uiText(UiR.string.ui_hide_details_f8c24) else uiText(UiR.string.ui_all_passes_and_runtime_a99e0)) }
            if (details) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiText(UiR.string.ui_1_s_2_s_3_s_model_4_s_input_sha_256_5_s_run_6_s_42fa7, result.route, result.device, result.runtime, result.identity, result.inputHash, result.runId), style = MaterialTheme.typography.bodySmall)
                    result.computeMs.forEachIndexed { i, time ->
                        Text(uiText(UiR.string.ui_pass_1_s_2_s_ms_first_text_in_replay_3_s_ms_4_s_e7de9, i + 1, number(time), number(result.firstTextMs[i]), result.texts[i]), style = MaterialTheme.typography.bodySmall)
                    }
                    result.samples.forEach { sample ->
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("${sample.id} · ${number(sample.computeMs)} ms${if (sample.silence) " · ${uiText(UiR.string.benchmark_silence_check)}" else ""}",
                            style = MaterialTheme.typography.labelMedium)
                        sample.sourceText?.takeIf(String::isNotBlank)?.let {
                            Text("${uiText(UiR.string.benchmark_source_sample)}: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        sample.reference?.takeIf(String::isNotBlank)?.let {
                            val referenceLabel = if (result.target.isBlank()) UiR.string.benchmark_reference_transcript else UiR.string.benchmark_reference_translation
                            Text("${uiText(referenceLabel)}: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(sample.text.ifBlank { uiText(UiR.string.ui_no_speech_recognized_a_fast_empty_result_is_not_a_quality_win_c93af) }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
