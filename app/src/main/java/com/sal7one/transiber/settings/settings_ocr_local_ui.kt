package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import com.sal7one.transiber.ocr.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsOcrLocalUi(onDownloads: () -> Unit = {}, selected: String? = null, onSelect: ((String) -> Unit)? = null) {
    val uiText = rememberUiText()

    val context=LocalContext.current;val scope=rememberCoroutineScope();val models=remember { OcrModels(File(context.filesDir,"ocr-models")) }
    var revision by remember { mutableIntStateOf(0) };var importing by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    var family by rememberSaveable { mutableStateOf(OcrCatalog.profiles.firstOrNull {it.id==selected}?.engine ?: "paddle") }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) scope.launch {
        importing=true
        try { withContext(Dispatchers.IO) { val job=currentCoroutineContext();uris.forEach { uri -> context.contentResolver.openInputStream(uri)?.use { models.import(it) { job.ensureActive() } } ?: error(uiText(UiR.string.ui_cannot_open_ocr_model_764c9)) } };revision++;error=null }
        catch(e: CancellationException){throw e} catch(e: Exception){error=e.message ?: e.toString()} finally{importing=false}
    } }
    LaunchedEffect(Unit) { while(true){ delay(1500);revision++ } }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(uiText(UiR.string.ui_ocr_camera_reading_12439),style=MaterialTheme.typography.titleLarge)
        Text(if(ByokPolicy.FEATURE_BYOK) uiText(UiR.string.ui_paddle_for_general_text_manga_for_japanese_bubbles_meiki_for_japa_76763) else uiText(UiR.string.ui_import_the_files_for_your_selected_ocr_engine_below_this_offline_7e9b3))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("paddle" to "Paddle", "manga" to "Manga", "meiki" to "Meiki").forEach {(id,label)->
                FilterChip(selected=family==id,onClick={family=id},label={Text(label)})
            }
        }
        OcrCatalog.profiles.filter {it.engine==family}.forEach { profile ->
            var showFiles by remember(profile.id) {mutableStateOf(false)}
            val ready=remember(revision,profile.id){models.ready(profile)}
            val pending=remember(revision,profile.id) {
                if(ByokPolicy.FEATURE_BYOK) FileDownloads(context).list().filter {
                    it.title in profile.assets.map { asset -> asset.filename } && it.active
                } else emptyList()
            }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(uiText.label(profile),style=MaterialTheme.typography.titleMedium)
                Text(uiText.description(profile))
                Text(uiText(UiR.string.ui_1_s_mib_total_2_s_cee22, "%.1f".format(profile.assets.sumOf { it.bytes }/1048576.0), if(ready) uiText(UiR.string.model_installed) else uiText(UiR.string.model_needs_files, profile.assets.size)))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(onSelect != null) FilterChip(selected=selected==profile.id,onClick={onSelect(profile.id)},label={Text(if(selected==profile.id) uiText(UiR.string.ui_selected_9a976) else uiText(UiR.string.ui_choose_78b7c))})
                    if(pending.isNotEmpty()) OutlinedButton(onClick=onDownloads) { Text(uiText(UiR.string.ui_view_download_progress_fb5a1)) }
                    if(ByokPolicy.FEATURE_BYOK && !ready && pending.isEmpty()) Button(onClick={
                        try {
                            val downloads=FileDownloads(context)
                            models.missing(profile).forEach { asset ->
                                if(downloads.list().none { it.active && it.title == asset.filename })downloads.enqueue(DownloadSpec(asset.url,asset.filename),modelPackage=true,installModelId=asset.id)
                            };error=null;revision++
                        } catch(e: Exception){error=e.message ?: e.toString()}
                    },enabled=!importing) { Text(uiText(UiR.string.ui_download_install_7db0b)) }
                }
                pending.forEach { Text("${it.title}: ${it.phase} · ${it.bytes/1048576} MiB",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={showFiles=!showFiles}) {Text(if(showFiles) uiText(UiR.string.ui_hide_file_links_216d2) else uiText(UiR.string.ui_files_import_links_72993))}
                if(showFiles)profile.assets.forEach {asset->
                    Text("${asset.remoteFile.substringAfterLast('/')} · ${"%.1f".format(asset.bytes/1048576.0)} MiB",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(asset.filename,asset.url))}) {Text(uiText(UiR.string.ui_copy_exact_file_link_9e1fe))}
                }
                profile.assets.distinctBy { it.owner+"/"+it.repository }.forEach {asset->
                    val source="https://huggingface.co/${asset.owner}/${asset.repository}"
                    if(ByokPolicy.FEATURE_BYOK) TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(source)))}) {Text(uiText(UiR.string.ui_source_license_1_s_98997, asset.repository))}
                    else Text(source,style=MaterialTheme.typography.bodySmall)
                }
            } }
        }
        OutlinedButton(onClick={importer.launch(arrayOf("*/*"))},enabled=!importing) { Text(if(importing) uiText(UiR.string.ui_verifying_model_fa771) else uiText(UiR.string.ui_import_downloaded_onnx_files_1f3bd)) }
        Text(uiText(UiR.string.ui_select_all_onnx_files_for_the_selected_engine_together_file_names_c1ec6),style=MaterialTheme.typography.bodySmall)
        if(ByokPolicy.FEATURE_BYOK) TextButton(onClick=onDownloads) { Text(uiText(UiR.string.ui_downloads_folder_settings_668bd)) }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
