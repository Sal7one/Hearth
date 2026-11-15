package com.sal7one.transiber.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.core.models.ModelPerformanceCatalog
import com.sal7one.hearth.core.models.PerformancePlatform
import com.sal7one.hearth.core.models.PerformanceTask
import com.sal7one.transiber.R
import com.sal7one.transiber.benchmark.BenchmarkResult
import com.sal7one.transiber.benchmark.BenchmarkResults
import com.sal7one.transiber.benchmark.latestBenchmarkSpeed
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.i18n.languageName
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.i18n.modelSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

internal fun modelSpeedReferences(id: String, source: String = "", target: String = "") =
    ModelPerformanceCatalog.forModel(id, PerformancePlatform.ANDROID).filter { row ->
        val language = row.language.orEmpty()
        (source.isBlank() || source == "any" || source == "auto" || language.substringBefore('→') == source) &&
            (target.isBlank() || target == "any" || language.substringAfter('→', "") == target)
    }

@Composable
internal fun ModelSpeedLine(modelId: String, source: String = "", target: String = "") {
    val ui = rememberUiText()
    modelSpeedReferences(modelId, source, target).firstOrNull()?.let { row ->
        Text(ui(R.string.model_speed_reference_value, row.label, row.context),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ModelSpeedPanel(initialSection: String, onBenchmark: (() -> Unit)?) {
    val ui = rememberUiText()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sections = mapOf("Speech" to PerformanceTask.SPEECH, "Translation" to PerformanceTask.TRANSLATION,
        "Camera" to PerformanceTask.OCR, "Voices" to PerformanceTask.VOICE)
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    val task = sections[section] ?: PerformanceTask.SPEECH
    var local by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<BenchmarkResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val showHistory = local && task != PerformanceTask.OCR
    LaunchedEffect(showHistory) {
        error = null
        if (showHistory && history == null) {
            try { history = withContext(Dispatchers.IO) { BenchmarkResults(context.applicationContext).load() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.toString() }
        }
    }

    Text(ui(R.string.model_speed_title), style = MaterialTheme.typography.headlineSmall)
    Text(ui(R.string.model_speed_explanation), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.keys.forEach { name ->
            FilterChip(section == name, { section = name }, label = { Text(ui.modelSection(name)) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!local, { local = false }, label = { Text(ui(R.string.model_speed_reference)) })
        FilterChip(local, { local = true }, label = { Text(ui(R.string.model_speed_this_device)) })
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (local) {
        val results = history
        if (task == PerformanceTask.OCR) Text(ui(R.string.model_speed_no_reference))
        else if (results == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (results != null) {
            val rows = remember(results, task) { latestBenchmarkSpeed(results, task.name.lowercase()) }
            val first = rows.firstOrNull()?.result
            if (first == null) Text(ui(R.string.model_speed_no_local))
            else {
                val language = listOf(first.source, first.target).filter(String::isNotBlank)
                    .joinToString(" → ") { ui.languageName(it) }
                Text(ui(R.string.model_speed_latest_cohort, language, first.device), style = MaterialTheme.typography.bodySmall)
                if (task == PerformanceTask.SPEECH && first.audioMs > 0) {
                    Text(ui(R.string.model_speed_input_audio, String.format(ui.locale, "%.1f", first.audioMs / 1000.0)),
                        style = MaterialTheme.typography.bodySmall)
                } else if (first.samples.isNotEmpty()) {
                    Text(ui(R.string.model_speed_input_samples, first.samples.size), style = MaterialTheme.typography.bodySmall)
                }
                rows.take(8).forEach { (result, ms) ->
                    val backend = result.actualAcceleration?.takeIf(String::isNotBlank) ?: ui(R.string.model_speed_unknown_backend)
                    Text(ui(R.string.model_speed_local_value, result.model, ms.roundToLong(), backend), style = MaterialTheme.typography.bodyMedium)
                }
                if (rows.size > 8) Text(ui(R.string.model_speed_more, rows.size - 8), style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        val cohorts = remember(task) {
            ModelPerformanceCatalog.forPlatform(PerformancePlatform.ANDROID)
                .filter { it.task == task && (ByokPolicy.FEATURE_BYOK || it.modelId != "ml-kit") }.groupBy { it.cohort }
        }
        if (cohorts.isEmpty()) Text(ui(R.string.model_speed_no_reference))
        cohorts.values.forEach { rows ->
            val first = rows.first()
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(first.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.sortedBy { it.elapsedMs }.forEach { row ->
                    Text("${row.title} · ${row.label}", style = MaterialTheme.typography.bodyMedium)
                }
                if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = {
                    try { uriHandler.openUri(first.sourceUrl); error = null }
                    catch (failure: Exception) { error = failure.message ?: failure.toString() }
                }) { Text(ui(R.string.model_speed_source)) }
                else SelectionContainer {
                    Text(first.sourceUrl, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
    if (onBenchmark != null && task != PerformanceTask.OCR) {
        OutlinedButton(onClick = onBenchmark) { Text(ui(R.string.model_speed_open_benchmark)) }
    }
}
