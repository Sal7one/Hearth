package com.sal7one.transiber.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText

/** The parent keeps this outside its scrollable tabs so Run and its blocker stay visible. */
@Composable
internal fun BenchmarkRunFooter(
    issue: BenchmarkReadinessIssue?,
    selectedCount: Int,
    measuredPasses: Int,
    inputSummary: String,
    compareAcceleration: Boolean,
    running: Boolean,
    stopping: Boolean,
    status: String,
    gateOwner: String?,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onFix: (BenchmarkReadinessFix) -> Unit,
    onEditInput: () -> Unit,
    onUseBundledSpeech: (() -> Unit)? = null,
    runError: String? = null,
    recovering: Boolean = false,
    onRecover: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiText = rememberUiText()
    Surface(modifier.fillMaxWidth().testTag("benchmark-run-footer"), tonalElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(uiText(R.string.benchmark_lab_run_summary, selectedCount, measuredPasses) +
                if (compareAcceleration) uiText(R.string.benchmark_lab_backend_suffix) else "",
                style = MaterialTheme.typography.labelLarge)
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("benchmark-run-status").semantics { liveRegion = LiveRegionMode.Polite })
                Button(onStop, enabled = !stopping, modifier = Modifier.fillMaxWidth().testTag("benchmark-stop")) {
                    Text(uiText(if (stopping) R.string.benchmark_lab_stopping else R.string.benchmark_lab_stop))
                }
            } else {
                TextButton(onEditInput, modifier = Modifier.testTag("benchmark-edit-input"),
                    contentPadding = PaddingValues(vertical = 0.dp, horizontal = 0.dp)) {
                    Text(uiText(R.string.benchmark_ready_input_summary, inputSummary), style = MaterialTheme.typography.bodySmall)
                }
                if (runError != null) Text(runError, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("benchmark-run-error").semantics { liveRegion = LiveRegionMode.Polite })
                if (recovering) {
                    Text(uiText(R.string.benchmark_lab_recovery_hint), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("benchmark-recovery-hint"))
                    TextButton(onRecover, enabled = !stopping, modifier = Modifier.testTag("benchmark-retry-cleanup"),
                        contentPadding = PaddingValues(vertical = 0.dp, horizontal = 0.dp)) {
                        Text(uiText(if (stopping) R.string.benchmark_lab_confirming_cleanup else R.string.benchmark_lab_retry_cleanup))
                    }
                }
                if (issue != null && !(recovering && issue == BenchmarkReadinessIssue.ENGINE_IN_USE)) {
                    val reason = when (issue) {
                        BenchmarkReadinessIssue.INITIALIZING -> uiText(R.string.benchmark_ready_initializing)
                        BenchmarkReadinessIssue.PREPARING_INPUT -> uiText(R.string.benchmark_ready_preparing)
                        BenchmarkReadinessIssue.ENGINE_IN_USE -> uiText(R.string.benchmark_ready_engine_in_use, gateOwner.orEmpty())
                        BenchmarkReadinessIssue.NO_MODELS -> uiText(R.string.benchmark_ready_no_models)
                        BenchmarkReadinessIssue.UNKNOWN_SAMPLE_LANGUAGE -> uiText(R.string.benchmark_ready_unknown_sample_language)
                        BenchmarkReadinessIssue.EMPTY_SAUDI_SET -> uiText(R.string.benchmark_ready_empty_saudi)
                        BenchmarkReadinessIssue.NO_BUILT_IN_SAMPLES -> uiText(R.string.benchmark_ready_no_samples)
                        BenchmarkReadinessIssue.NO_AUDIO -> uiText(R.string.benchmark_ready_no_audio)
                        BenchmarkReadinessIssue.NO_TEXT -> uiText(R.string.benchmark_ready_no_text)
                        BenchmarkReadinessIssue.SAME_LANGUAGE_PAIR -> uiText(R.string.benchmark_ready_same_pair)
                    }
                    Text(reason, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("benchmark-run-reason").semantics { liveRegion = LiveRegionMode.Polite })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (issue.fix != BenchmarkReadinessFix.NONE) TextButton({ onFix(issue.fix) },
                            modifier = Modifier.testTag("benchmark-fix"),
                            contentPadding = PaddingValues(vertical = 0.dp, horizontal = 0.dp)) {
                            Text(uiText(when {
                                issue == BenchmarkReadinessIssue.EMPTY_SAUDI_SET -> R.string.benchmark_ready_record_import
                                issue.fix == BenchmarkReadinessFix.MODELS -> R.string.benchmark_lab_choose
                                issue.fix == BenchmarkReadinessFix.STOP_ACTIVITY -> R.string.benchmark_ready_stop_activity
                                else -> R.string.benchmark_ready_edit_input
                            }))
                        }
                        if (issue == BenchmarkReadinessIssue.EMPTY_SAUDI_SET && onUseBundledSpeech != null) {
                            TextButton(onUseBundledSpeech, modifier = Modifier.testTag("benchmark-use-bundled"),
                                contentPadding = PaddingValues(vertical = 0.dp, horizontal = 0.dp)) {
                                Text(uiText(R.string.benchmark_ready_use_bundled_arabic))
                            }
                        }
                    }
                }
                Button(onRun, enabled = issue == null && !stopping && !recovering,
                    modifier = Modifier.fillMaxWidth().testTag("benchmark-compare")) {
                    Text(uiText(R.string.ui_compare_1_s_models_1916c, selectedCount))
                }
            }
        }
    }
}
