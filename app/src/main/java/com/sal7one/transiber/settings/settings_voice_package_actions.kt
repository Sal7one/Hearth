package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*
import java.io.File

/** FileDownloads.list supplies newest first; an active copy takes precedence over past failures. */
internal fun voicePackageRecords(rows: List<FileDownload>,ids: Set<String>) = rows.filter {it.modelId in ids}
    .groupBy {it.modelId}.values.map {copies -> copies.firstOrNull {it.active} ?: copies.first()}

/** One primary package action, with real queue state and secondary file controls. No activation. */
@Composable
internal fun VoicePackageActions(family: String,ready: Boolean,available: Boolean,revision: Int,
    downloadLabel: String,importLabel: String,onError: (String?)->Unit,onImport: ()->Unit,
    onDownloads: ()->Unit,onRevision: ()->Unit,downloadAdditional: suspend ()->Unit = {}) {
    val text=rememberUiText();val context=LocalContext.current;val scope=rememberCoroutineScope()
    var working by remember(family) {mutableStateOf(false)}
    var rows by remember(family) {mutableStateOf<List<FileDownload>>(emptyList())}
    val assets=VoiceCatalog.familyAssets(family)
    LaunchedEffect(family,revision) {
        if(ByokPolicy.FEATURE_BYOK)try {rows=withContext(Dispatchers.IO){FileDownloads(context).list()}}
        catch(e: CancellationException){throw e}catch(e: Exception){onError(e.message ?: e.toString())}
    }
    val relevant=voicePackageRecords(rows,assets.map {it.id}.toSet())
    val active=relevant.filter {it.active};val failure=relevant.firstOrNull {it.failed}
    if(!ready)failure?.let {Text(it.error.ifBlank {text(R.string.ui_download_failed_reason_1_s_8bb99,it.reason)},
        color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    if(ByokPolicy.FEATURE_BYOK && active.isNotEmpty())OutlinedButton(onClick=onDownloads) {
        Text(text(R.string.ui_download_progress_1_s_files_cdb9d,active.size))
    } else if(ByokPolicy.FEATURE_BYOK && !ready) {
        if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled=available&&!working,onClick={scope.launch {
            working=true;onError(null)
            try {
                withContext(Dispatchers.IO) {
                    LocalVoiceFamilies.requireAllowed(context,family,"speech model download")
                    val downloads=FileDownloads(context);val installed=VoiceModels(File(context.filesDir,"voice-models"))
                    assets.filterNot(installed::installed).forEach {downloads.enqueue(DownloadSpec(it.url,it.filename),true,it.id)}
                }
                downloadAdditional()
            }catch(e: CancellationException){throw e}catch(e: Exception){onError(e.message ?: e.toString())}
            finally{working=false;onRevision()}
        }}){Text(if(failure!=null)text(R.string.ui_retry_download_install_e5444) else downloadLabel)}
    }
    VoiceFileOptions(available&&!working,importLabel,onImport,onDownloads)
}
