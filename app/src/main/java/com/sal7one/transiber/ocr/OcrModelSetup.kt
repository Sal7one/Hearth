package com.sal7one.transiber.ocr

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
internal fun OcrModelSetup(onDownloads: () -> Unit = {}, selected: String? = null, onSelect: ((String) -> Unit)? = null) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val models=remember { OcrModels(File(context.filesDir,"ocr-models")) }
    var revision by remember { mutableIntStateOf(0) };var importing by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    var family by rememberSaveable { mutableStateOf(OcrCatalog.profiles.firstOrNull {it.id==selected}?.engine ?: "paddle") }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) scope.launch {
        importing=true
        try { withContext(Dispatchers.IO) { val job=currentCoroutineContext();context.contentResolver.openInputStream(uri)?.use { models.import(it) { job.ensureActive() } } ?: error("Cannot open OCR model") };revision++;error=null }
        catch(e: CancellationException){throw e} catch(e: Exception){error=e.message ?: e.toString()} finally{importing=false}
    } }
    LaunchedEffect(Unit) { while(true){ delay(1500);revision++ } }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("OCR · Camera & reading",style=MaterialTheme.typography.titleLarge)
        Text(if(ByokPolicy.FEATURE_BYOK) "Paddle for general text, Manga for Japanese bubbles, Meiki for Japanese lines. Each choice installs all of its required files. Downloads install automatically; originals stay in Downloads/Hearth/models or your chosen folder." else "Import the files for your selected OCR engine below. This offline build does not download files or contact model publishers.")
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
                Text(profile.label,style=MaterialTheme.typography.titleMedium)
                Text(profile.description)
                Text("${"%.1f".format(profile.assets.sumOf { it.bytes }/1048576.0)} MiB total · ${if(ready) "Installed" else "Needs ${profile.assets.size} model files"}")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(onSelect != null) FilterChip(selected=selected==profile.id,onClick={onSelect(profile.id)},label={Text(if(selected==profile.id) "Selected" else "Choose")})
                    if(pending.isNotEmpty()) OutlinedButton(onClick=onDownloads) { Text("View download progress") }
                    if(ByokPolicy.FEATURE_BYOK && !ready && pending.isEmpty()) Button(onClick={
                        try {
                            val downloads=FileDownloads(context)
                            models.missing(profile).forEach { asset ->
                                if(downloads.list().none { it.active && it.title == asset.filename })downloads.enqueue(DownloadSpec(asset.url,asset.filename),modelPackage=true,installModelId=asset.id)
                            };error=null;revision++
                        } catch(e: Exception){error=e.message ?: e.toString()}
                    },enabled=!importing) { Text("Download & install") }
                }
                pending.forEach { Text("${it.title}: ${it.phase} · ${it.bytes/1048576} MiB",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={showFiles=!showFiles}) {Text(if(showFiles) "Hide file links" else "Files & import links")}
                if(showFiles)profile.assets.forEach {asset->
                    Text("${asset.remoteFile.substringAfterLast('/')} · ${"%.1f".format(asset.bytes/1048576.0)} MiB",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(asset.filename,asset.url))}) {Text("Copy exact file link")}
                }
                profile.assets.distinctBy { it.owner+"/"+it.repository }.forEach {asset->
                    val source="https://huggingface.co/${asset.owner}/${asset.repository}"
                    if(ByokPolicy.FEATURE_BYOK) TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(source)))}) {Text("Source & license · ${asset.repository}")}
                    else Text(source,style=MaterialTheme.typography.bodySmall)
                }
            } }
        }
        OutlinedButton(onClick={importer.launch(arrayOf("*/*"))},enabled=!importing) { Text(if(importing) "Verifying model…" else "Import downloaded ONNX file") }
        Text("Import each ONNX file listed for the selected engine. File names do not matter: Hearth identifies and verifies the exact model bytes. Matching dictionaries are included. Manga and Meiki currently use captured/imported pages; live mode remains available with Paddle.",style=MaterialTheme.typography.bodySmall)
        if(ByokPolicy.FEATURE_BYOK) TextButton(onClick=onDownloads) { Text("Downloads & folder settings") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
