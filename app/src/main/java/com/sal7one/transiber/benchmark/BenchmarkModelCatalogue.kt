package com.sal7one.transiber.benchmark

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.i18n.uiText
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.models.ModelHardwareFilter
import com.sal7one.transiber.models.speechGpuPaths
import com.sal7one.transiber.models.translationGpuPaths
import com.sal7one.transiber.ui.components.ModelHardwarePicker
import com.sal7one.transiber.ui.components.ModelGpuPaths
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.voice.LocalVoiceFamilies
import com.sal7one.transiber.voice.VoiceCatalog
import java.util.Locale

/** Only pinned, executable catalogue entries; imports remain available through Models. */
internal data class BenchmarkCatalogueEntry(
    val model: SuggestedModel,
    val family: String,
    val sources: Set<String>,
    val targets: Set<String> = emptySet(),
    val unavailable: String? = null,
    val note: String? = null,
    val gpuPaths: Set<String> = emptySet(),
) {
    fun supports(source: String, target: String): Boolean = source in sources &&
        (targets.isEmpty() || when (model.kind) {
            "gguf" -> TranslationCatalog.models.first { it.id == model.id }.supports(source, target)
            "ct2" -> Ct2Catalog.find(model.id)?.supports(source, target) == true
            else -> target in targets && source != target
        })
}

internal object BenchmarkModelCatalogue {
    /** Call from IO: runtime availability can inspect native library loading. */
    fun entries(context: Context, mode: String): List<BenchmarkCatalogueEntry> = context.uiText().let { text -> when (mode) {
        "Speech" -> SpeechArtifactCatalog.all.map { artifact ->
            val supported = Build.SUPPORTED_ABIS.any { it in artifact.supportedAbis } &&
                (artifact.supportedAbis != setOf("arm64-v8a") || Process.is64Bit())
            BenchmarkCatalogueEntry(SuggestedModel(artifact.id, artifact.label, artifact.bytes, "speech"),
                artifact.family, artifact.languages + if (artifact.languages.size > 1) setOf("auto") else emptySet(),
                unavailable = if (supported) null else text(UiR.string.benchmark_lab_requires_abi, artifact.supportedAbis.joinToString()),
                note = if (artifact.advanced) text(UiR.string.benchmark_lab_experimental_note) else null,
                gpuPaths = speechGpuPaths(artifact))
        }
        "Voices" -> LocalVoiceFamilies.providers.map { family ->
            BenchmarkCatalogueEntry(SuggestedModel(family.id, family.label,
                VoiceCatalog.familyAssets(family.id).sumOf { it.bytes }, "voice"), family.label, family.languages,
                unavailable = if (LocalVoiceFamilies.available(context, family.id)) null else
                    text(UiR.string.benchmark_lab_voice_enable),
                note = if (family.id == "magpie") text(UiR.string.benchmark_lab_magpie_note) else
                    text(UiR.string.benchmark_lab_voice_bundle_note))
        }
        else -> TranslationCatalog.models.map { spec ->
            BenchmarkCatalogueEntry(SuggestedModel(spec.id, spec.label, spec.bytes, "gguf"), spec.family,
                spec.sourceLanguages, spec.targetLanguages, gpuPaths = translationGpuPaths(true))
        } + MarianPackage.pairs.map { pair ->
            BenchmarkCatalogueEntry(SuggestedModel(pair.id, TranslationOptions.label(pair.id), pair.downloadBytes, "marian"),
                "Marian ONNX", setOf(pair.source), setOf(pair.target))
        } + MarianCascade.routes.map { route ->
            BenchmarkCatalogueEntry(SuggestedModel(route.id, TranslationOptions.label(route.id), route.downloadBytes, "cascade"),
                "Marian via English", setOf(route.source), setOf(route.target), note = text(UiR.string.benchmark_lab_pivot_note))
        } + Ct2Catalog.models.map { spec ->
            BenchmarkCatalogueEntry(SuggestedModel(spec.id, spec.selectionLabel, spec.bytes, "ct2"), "CTranslate2", spec.sources, spec.targets,
                unavailable = when {
                    !Ct2Package.runtimeSupported() -> text(UiR.string.benchmark_lab_requires_arm64)
                    !spec.downloadEnabled -> text(UiR.string.benchmark_lab_import_runtime)
                    else -> null
                })
        }
    } }
}

/** Full-screen lazy selection keeps large catalogues usable without narrowing owner choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BenchmarkCatalogueDialog(
    entries: List<BenchmarkCatalogueEntry>,
    source: String,
    target: String,
    installedIds: Set<String>,
    records: List<FileDownload>,
    selected: List<String>,
    onSelection: (List<String>) -> Unit,
    freeBytes: Long,
    queueing: Boolean,
    queueStatus: String,
    queueErrors: List<String>,
    onQueue: (List<SuggestedModel>) -> Unit,
    onPause: (List<FileDownload>) -> Unit,
    onDownloads: () -> Unit,
    onModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uiText = rememberUiText()
    fun size(bytes: Long?) = bytes?.let(::benchmarkBytes) ?: uiText(UiR.string.benchmark_lab_size_unknown)
    fun familyLabel(value: String) = if (value == "Marian via English") uiText(UiR.string.benchmark_lab_marian_pivot) else value
    fun phaseLabel(value: String) = when (value) {
        "Downloading" -> uiText(UiR.string.benchmark_transfer_downloading)
        "Verifying" -> uiText(UiR.string.benchmark_transfer_verifying)
        "Installing" -> uiText(UiR.string.benchmark_transfer_installing)
        "Waiting" -> uiText(UiR.string.benchmark_transfer_waiting)
        "Paused" -> uiText(UiR.string.benchmark_transfer_paused)
        "Failed", "Interrupted" -> uiText(UiR.string.benchmark_transfer_failed)
        "Complete" -> uiText(UiR.string.benchmark_lab_phase_complete)
        "Installed" -> uiText(UiR.string.benchmark_lab_phase_installed)
        else -> value
    }
    var query by rememberBenchmarkString("catalogue-query", "")
    var compatibleOnly by rememberBenchmarkBoolean("catalogue-compatible", true)
    var family by rememberBenchmarkString("catalogue-family-${entries.firstOrNull()?.model?.kind.orEmpty()}", "")
    var sortBySize by rememberBenchmarkBoolean("catalogue-smallest-first", false)
    var hardwareKey by rememberBenchmarkString("catalogue-hardware", ModelHardwareFilter.ALL.key)
    val hardware = ModelHardwareFilter.fromKey(hardwareKey)
    var confirmBatch by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    val visible = remember(entries, source, target, query, compatibleOnly, family, sortBySize, hardware) {
        entries.filter { row ->
            (!compatibleOnly || row.supports(source, target)) && (family.isBlank() || row.family == family) && hardware.matches(row.gpuPaths) &&
                (query.isBlank() || listOf(row.model.label, row.model.id, row.family).any { it.contains(query, true) })
        }.let { rows -> if (sortBySize) rows.sortedBy { it.model.bytes ?: Long.MAX_VALUE } else rows }
    }
    val chosen = entries.filter { it.model.id in selected && it.model.id !in installedIds && it.unavailable == null }
    val chosenBytes = chosen.sumOf { it.model.bytes ?: 0L }
    val selectable = visible.filter { it.unavailable == null && it.model.id !in installedIds }.map { it.model.id }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(uiText(UiR.string.benchmark_lab_catalogue), style = MaterialTheme.typography.headlineSmall)
                            Text(uiText(UiR.string.benchmark_lab_catalogue_summary, entries.size, size(freeBytes)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onDismiss) { Text(uiText(UiR.string.benchmark_lab_done)) }
                    }
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(uiText(UiR.string.benchmark_lab_search_catalogue)) })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(compatibleOnly, { compatibleOnly = !compatibleOnly }, label = { Text(if (entries.any { it.targets.isNotEmpty() }) uiText(UiR.string.benchmark_lab_this_pair) else uiText(UiR.string.benchmark_lab_this_language)) })
                        FilterChip(sortBySize, { sortBySize = !sortBySize }, label = { Text(uiText(UiR.string.benchmark_lab_smallest)) })
                        var familyMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton({ familyMenu = true }) { Text(if (family.isBlank()) uiText(UiR.string.benchmark_lab_all_families) else familyLabel(family)) }
                            DropdownMenu(familyMenu, { familyMenu = false }) {
                                DropdownMenuItem({ Text(uiText(UiR.string.benchmark_lab_all_families)) }, { family = ""; familyMenu = false })
                                entries.map { it.family }.distinct().sorted().forEach { value ->
                                    DropdownMenuItem({ Text(familyLabel(value)) }, { family = value; familyMenu = false })
                                }
                            }
                        }
                    }
                    ModelHardwarePicker(hardware, { hardwareKey = it })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiText(UiR.string.benchmark_lab_shown_summary, visible.size, chosen.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton({ onSelection((selected + selectable).distinct()) }, enabled = !queueing && selectable.isNotEmpty()) { Text(uiText(UiR.string.benchmark_lab_select_shown)) }
                        TextButton({ onSelection(emptyList()) }, enabled = !queueing && selected.isNotEmpty()) { Text(uiText(UiR.string.benchmark_lab_clear)) }
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (visible.isEmpty()) item { Text(uiText(UiR.string.benchmark_lab_no_catalogue_matches)) }
                    items(visible, key = { it.model.id }) { entry ->
                        val installed = entry.model.id in installedIds
                        val transfer = BenchmarkDownloadProgress.forModel(entry.model, records)
                        val selectableRow = !queueing && !installed && entry.unavailable == null
                        OutlinedCard(Modifier.fillMaxWidth().toggleable(entry.model.id in selected, enabled = selectableRow, role = Role.Checkbox) { checked ->
                            onSelection(if (checked) (selected + entry.model.id).distinct() else selected - entry.model.id)
                        }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(entry.model.id in selected, null, enabled = selectableRow)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(entry.model.label, style = MaterialTheme.typography.titleSmall)
                                        Text(uiText(UiR.string.benchmark_lab_family_size, familyLabel(entry.family), size(entry.model.bytes)), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                ModelGpuPaths(entry.gpuPaths)
                                if (installed) Text(uiText(UiR.string.benchmark_lab_installed_ready), color = MaterialTheme.colorScheme.primary)
                                else if (entry.unavailable != null) Text(entry.unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                                else if (transfer != null) {
                                    Text(uiText(UiR.string.benchmark_lab_transfer_summary, phaseLabel(transfer.currentPhase), transfer.doneFiles, transfer.expectedFiles, size(transfer.bytes)),
                                        style = MaterialTheme.typography.bodySmall)
                                    if (transfer.active) {
                                        transfer.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                                            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                                        TextButton({ onPause(transfer.records.filter(FileDownload::active)) }) { Text(uiText(UiR.string.benchmark_lab_pause_download)) }
                                    }
                                    transfer.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                }
                                if (!entry.supports(source, target)) Text(uiText(UiR.string.benchmark_lab_change_language), style = MaterialTheme.typography.bodySmall)
                                entry.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(uiText(UiR.string.benchmark_lab_batch_size, chosen.size, size(chosenBytes)), style = MaterialTheme.typography.titleSmall)
                        Text(uiText(UiR.string.benchmark_lab_storage_hint), style = MaterialTheme.typography.bodySmall)
                        if (queueStatus.isNotBlank()) Text(queueStatus, style = MaterialTheme.typography.bodySmall)
                        if (queueErrors.isNotEmpty()) TextButton({ showErrors = true }) { Text(uiText(UiR.string.benchmark_lab_queue_errors_count, queueErrors.size)) }
                        if (queueing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (ByokPolicy.FEATURE_BYOK) Button({ confirmBatch = true }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && !queueing) {
                            Text(uiText(UiR.string.benchmark_lab_queue_models, chosen.size))
                        } else Text(uiText(UiR.string.benchmark_lab_offline_hint), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (ByokPolicy.FEATURE_BYOK) TextButton(onDownloads) { Text(uiText(UiR.string.benchmark_lab_manage_downloads)) }
                            TextButton(onModels) { Text(uiText(UiR.string.benchmark_lab_import_details)) }
                        }
                    }
                }
            }
        }
    }
    if (showErrors) AlertDialog(onDismissRequest = { showErrors = false }, title = { Text(uiText(UiR.string.benchmark_lab_queue_errors)) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(queueErrors) { Text(it) } } },
        confirmButton = { TextButton({ showErrors = false }) { Text(uiText(UiR.string.benchmark_lab_close)) } })
    if (confirmBatch) AlertDialog(onDismissRequest = { confirmBatch = false }, title = { Text(uiText(UiR.string.benchmark_lab_confirm_queue, chosen.size)) },
        text = { Text(uiText(UiR.string.benchmark_lab_confirm_queue_hint, size(chosenBytes), size(freeBytes))) },
        confirmButton = { Button({ confirmBatch = false; onQueue(chosen.map { it.model }) }) { Text(uiText(UiR.string.benchmark_lab_queue_downloads)) } },
        dismissButton = { TextButton({ confirmBatch = false }) { Text(uiText(UiR.string.benchmark_lab_keep_choosing)) } })
}

internal fun benchmarkBytes(bytes: Long?): String = when {
    bytes == null -> "size unknown"
    bytes >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.2f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MiB", bytes / 1_048_576.0)
    else -> "${bytes.coerceAtLeast(0) / 1024} KiB"
}
