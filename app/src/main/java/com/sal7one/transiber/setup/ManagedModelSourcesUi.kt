package com.sal7one.transiber.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.preferences.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Repair affordance is available in Basic; paths and grant diagnostics stay Advanced. */
@Composable
internal fun ModelBindingRepairNotice(onRepair: () -> Unit, launchError: String? = null) {
    val text = rememberUiText()
    val checks by ModelSourceBindings.checks.collectAsState()
    // An mtime change is informational, not evidence of missing/corrupt bytes.
    // Pinned runtime initialization still hashes the actual model before use.
    val affected = checks.count { !it.available }
    if (affected == 0 && launchError == null) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(text(R.string.setup_sources_attention), style = MaterialTheme.typography.titleSmall)
                Text(launchError ?: text(R.string.setup_sources_repair_detail, affected), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRepair) { Text(text(R.string.setup_sources_review)) }
        }
    }
}

@Composable
internal fun ManagedModelSourcesUi(initialExpanded: Boolean = false, onImport: (String) -> Unit) {
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(LocalContext.current)) return
    val text = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checks by ModelSourceBindings.checks.collectAsState()
    var expanded by remember { mutableStateOf(initialExpanded) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceResults by remember { mutableStateOf<Map<String, ModelSourceCheck>>(emptyMap()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(text(R.string.setup_sources_title)) }
        if (!expanded) return@Column
        Text(text(R.string.setup_sources_detail), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = { scope.launch {
            busy = true; error = null
            try { ModelSourceBindings.verifyAtLaunch(context) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        } }) { Text(text(R.string.setup_sources_recheck)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        checks.forEach { check ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(check.modelId, style = MaterialTheme.typography.titleSmall)
                    Text(text(when {
                        !check.available -> R.string.setup_sources_copy_missing
                        check.changedSinceImport -> R.string.setup_sources_copy_changed
                        else -> R.string.setup_sources_copy_ready
                    }), color = if (!check.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    check.paths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    check.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(text(when (check.sourceAccess) {
                        ModelSourceAccess.NOT_RECORDED -> R.string.setup_sources_original_unknown
                        ModelSourceAccess.GRANT_RETAINED_NOT_PROBED -> R.string.setup_sources_grant_retained
                        ModelSourceAccess.GRANT_MISSING -> R.string.setup_sources_grant_missing
                    }), style = MaterialTheme.typography.bodySmall)
                    if (check.sourceUri != null) TextButton(enabled = !busy, onClick = { scope.launch {
                        busy = true; error = null
                        try { sourceResults = sourceResults + (check.modelId to ModelSourceBindings.checkOriginalSource(context, check.modelId)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                        finally { busy = false }
                    } }) { Text(text(R.string.setup_sources_check_original)) }
                    sourceResults[check.modelId]?.let { result ->
                        Text(if (result.readable) text(R.string.setup_sources_original_readable)
                            else result.error ?: text(R.string.setup_sources_original_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.readable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                    if (!check.available) TextButton(onClick = { onImport(check.modelId) }) { Text(text(R.string.setup_sources_import_again)) }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
