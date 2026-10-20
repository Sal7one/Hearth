package com.sal7one.transiber.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.Ct2ModelSpec
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.models.ModelSearch
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.ui.components.ModelSourceLinks
import kotlinx.coroutines.*

internal fun ct2BrowserMatches(spec: Ct2ModelSpec,query: String,source: String,target: String,kind: String): Boolean =
    kind!="text" && (source=="any" || source in spec.sources) && (target=="any" || target in spec.targets) &&
        (source=="any" || target=="any" || spec.supports(source,target)) &&
        ModelSearch.matches(query,spec.id,spec.label,"CTranslate2 CT2 CPU compact Arabic English",spec.license,
            ModelSearch.languageTerms(spec.sources+spec.targets))

/** Compact packages use one card per directional model, independent of GGUF backend settings. */
@Composable
internal fun Ct2ModelsUi(query: String,source: String,target: String,kind: String,installedOnly: Boolean,
    selectedId: String,onSelect: (String) -> Unit,onError: (String) -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val text=rememberUiText()
    val downloads=remember { FileDownloads(context) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(emptyList<com.sal7one.transiber.downloads.FileDownload>()) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)scope.launch {
            busy=true
            try {
                val installed=withContext(Dispatchers.IO) {
                    val job=currentCoroutineContext()
                    val result=context.contentResolver.openInputStream(uri)?.use { Ct2Package.importZip(context,it) { job.ensureActive() } }
                        ?: error("Cannot open CT2 translation package")
                    com.sal7one.transiber.preferences.ModelSourceBindings.recordImported(context,result.first.id,uri,listOf(result.second))
                    result
                }
                revision++;onSelect(installed.first.id)
            } catch(e: CancellationException) { throw e } catch(e: Exception) { onError(generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() }) }
            finally { busy=false }
        }
    }
    LaunchedEffect(Unit) {
        while(isActive) {
            records=withContext(Dispatchers.IO) { if(ByokPolicy.FEATURE_BYOK)downloads.list() else emptyList() }
            revision++;delay(1500)
        }
    }
    Ct2Catalog.models.filter { ct2BrowserMatches(it,query,source,target,kind) }.forEach { spec ->
        val ready=remember(revision,spec) { Ct2Package.installed(context,spec) }
        if(!installedOnly || ready) {
            val parts=Ct2Package.parts(spec)
            val status=translationDownloadState(parts.map { it.id }.toSet(),records)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(spec.label,style=MaterialTheme.typography.titleMedium)
                    Text("${spec.bytes/1_048_576} MiB · CPU · ${if(spec.tokenizerMode==1) "100 languages" else "direct translation"}")
                    if(!Ct2Package.runtimeSupported()) Text("Requires a 64-bit ARM app process",style=MaterialTheme.typography.bodySmall)
                    Text("Experimental · phone quality and latency await testing",style=MaterialTheme.typography.bodySmall)
                    if(ready) Text(text(if(selectedId==spec.id) UiR.string.model_browser_selected else UiR.string.model_download_installed))
                    else if(status.total>0 && status.completed>0) Text(text(UiR.string.model_files_downloaded,status.completed,status.total))
                    status.failure?.let { Text(it.error, color=MaterialTheme.colorScheme.error) }
                    if(status.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(enabled=Ct2Package.runtimeSupported() && !busy && !status.active && selectedId!=spec.id,onClick={
                        if(!ready && !ByokPolicy.FEATURE_BYOK) importer.launch(arrayOf("application/zip","application/octet-stream"))
                        else scope.launch {
                            busy=true
                            try {
                                withContext(Dispatchers.IO) {
                                    if(ready) spec.verify(Ct2Package.directory(context,spec))
                                    else Ct2Package.enqueue(context,downloads,spec)
                                }
                                if(ready)onSelect(spec.id)
                            } catch(e: CancellationException) { throw e } catch(e: Exception) { onError(generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() }) }
                            finally { busy=false }
                        }
                    }) { Text(text(if(ready) UiR.string.translation_browser_use else if(ByokPolicy.FEATURE_BYOK) UiR.string.model_browser_download_install else UiR.string.translation_browser_import)) }
                    ModelSourceLinks(spec.modelCard,"https://huggingface.co/${spec.files.first().repo}/tree/${spec.files.first().revision}",spec.modelCard,onError=onError)
                    var details by remember { mutableStateOf(false) }
                    TextButton(onClick={details=!details}) { Text(text(if(details) UiR.string.model_browser_hide_details else UiR.string.model_browser_details)) }
                    if(details) {
                        Text("${spec.license} · ${spec.files.size} pinned files · Greedy decoding, two CPU threads. GPU settings for GGUF models do not apply.",style=MaterialTheme.typography.bodySmall)
                        if(spec.tokenizerMode==0)Text("FP16 stored weights are computed as FLOAT32 on CPU; RAM use exceeds the download size.",style=MaterialTheme.typography.bodySmall)
                        Text("ZIP imports require hearth-translation.json at the root, schemaVersion 1 and modelId ${spec.id}, plus the exact publisher files.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled=Ct2Package.runtimeSupported() && !busy,onClick={importer.launch(arrayOf("application/zip","application/octet-stream"))}) { Text(text(UiR.string.translation_browser_import)) }
                    }
                }
            }
        }
    }
}
