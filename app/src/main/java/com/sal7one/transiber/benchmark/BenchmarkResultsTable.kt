package com.sal7one.transiber.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import java.util.Locale
import kotlin.math.roundToInt

/** One table represents one exact input/device cohort, never unrelated saved runs. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BenchmarkResultsTable(
    leaders: BenchmarkLeaders?,
    source: String,
    target: String,
    sort: BenchmarkTableSort,
    onSort: (BenchmarkTableSort) -> Unit,
) {
    val uiText = rememberUiText()
    val rows = BenchmarkTableModel.sorted(leaders?.candidates.orEmpty(), sort)
    val minimumWarm = rows.mapNotNull(BenchmarkTableModel::warmMs).minOrNull()?.coerceAtLeast(0.01) ?: 1.0
    val maximumSpeed = rows.mapNotNull(BenchmarkTableModel::audioSpeed).maxOrNull()?.coerceAtLeast(0.01) ?: 1.0
    val speedColor = MaterialTheme.colorScheme.primary
    val qualityColor = MaterialTheme.colorScheme.tertiary
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText(R.string.benchmark_measured_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                Text(if (target.isBlank()) source.uppercase(Locale.ROOT)
                    else "${source.uppercase(Locale.ROOT)} → ${target.uppercase(Locale.ROOT)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (rows.isEmpty()) {
                Text(uiText(R.string.benchmark_table_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(uiText(R.string.benchmark_table_scope, rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (target.isBlank() && source == "ar") Text(uiText(
                    if (rows.first().samples.any { it.id.startsWith("saudi-") }) R.string.benchmark_saudi_set
                    else if (rows.first().samples.any { it.id.startsWith("fleurs-ar-") }) R.string.benchmark_egyptian_set
                    else R.string.benchmark_custom_input), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    BenchmarkTableSort.entries.forEach { choice ->
                        FilterChip(sort == choice, { onSort(choice) }, label = { Text(uiText(when (choice) {
                            BenchmarkTableSort.WARM -> R.string.benchmark_table_sort_warm
                            BenchmarkTableSort.LOAD -> R.string.benchmark_table_sort_load
                            BenchmarkTableSort.QUALITY -> R.string.benchmark_table_sort_quality
                            BenchmarkTableSort.RECENT -> R.string.benchmark_table_sort_recent
                        })) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TableHeading(uiText(R.string.benchmark_table_model), 2f)
                    TableHeading(uiText(if (target.isBlank()) R.string.benchmark_table_audio_speed else R.string.benchmark_table_each), 1f)
                    TableHeading(uiText(R.string.benchmark_table_load), 1f)
                    TableHeading(uiText(R.string.benchmark_table_reference), 1f)
                }
                HorizontalDivider()
                rows.forEachIndexed { index, result ->
                    val fastest = result.identity == leaders?.fastest?.identity
                    val bestReference = result.identity == leaders?.mostAccurate?.identity
                    val balanced = result.identity == leaders?.balanced?.identity
                    val rowColor = when {
                        fastest && bestReference -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        fastest -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        bestReference -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        balanced -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        index % 2 == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                        else -> Color.Transparent
                    }
                    val warm = BenchmarkTableModel.warmMs(result)
                    val quality = BenchmarkTableModel.qualityValue(result)
                    val qualityText = quality?.let {
                        if (result.target.isBlank()) uiText(R.string.benchmark_quality_errors, percent(it))
                        else uiText(R.string.benchmark_quality_similarity, percent(it))
                    } ?: "—"
                    val speed = BenchmarkTableModel.audioSpeed(result)
                    val speedText = speed?.let { String.format(Locale.getDefault(), "%.1f×", it) }
                        ?: BenchmarkTableModel.translationMsPerSentence(result)?.let(::elapsed) ?: "—"
                    Row(Modifier.fillMaxWidth().background(rowColor, MaterialTheme.shapes.small)
                        .heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${result.model}, ${uiText(if (target.isBlank()) R.string.benchmark_table_audio_speed else R.string.benchmark_table_each)} $speedText, " +
                                "${uiText(R.string.benchmark_table_load)} ${elapsed(result.loadMs)}, " +
                                "${uiText(R.string.benchmark_table_reference)} $qualityText"
                        }, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(2f)) {
                            Text(result.model, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            val badges = listOfNotNull(
                                uiText(R.string.benchmark_table_fastest).takeIf { fastest },
                                uiText(R.string.benchmark_table_balanced).takeIf { balanced },
                                uiText(R.string.benchmark_table_best_reference).takeIf { bestReference },
                            )
                            if (badges.isNotEmpty()) Text(badges.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = when { fastest -> speedColor; bestReference -> qualityColor
                                    else -> MaterialTheme.colorScheme.secondary })
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(speedText, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (warm != null) LinearProgressIndicator(
                                progress = { (if (speed != null) speed / maximumSpeed else minimumWarm / warm).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(), color = speedColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                        Text(elapsed(result.loadMs), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Text(qualityText, Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, maxLines = 2,
                            color = if (bestReference) qualityColor else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(uiText(R.string.benchmark_table_legend), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rows.any { it.protocolVersion < 2 }) Text(uiText(R.string.benchmark_old_timing),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RowScope.TableHeading(label: String, weight: Float) {
    Text(label, Modifier.weight(weight), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
}

private fun elapsed(ms: Double): String = if (!ms.isFinite() || ms < 0) "—"
    else if (ms < 1000) "${ms.roundToInt()}ms"
    else String.format(Locale.getDefault(), "%.2fs", ms / 1000.0)

private fun percent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value * 100.0)
