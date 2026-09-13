package com.sal7one.transiber.ocr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) scope.launch {
        importing=true
        try { withContext(Dispatchers.IO) { val job=currentCoroutineContext();context.contentResolver.openInputStream(uri)?.use { models.import(it) { job.ensureActive() } } ?: error("Cannot open OCR model") };revision++;error=null }
        catch(e: CancellationException){throw e} catch(e: Exception){error=e.message ?: e.toString()} finally{importing=false}
    } }
    LaunchedEffect(Unit) { while(true){ delay(1500);revision++ } }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Camera · PaddleOCR",style=MaterialTheme.typography.titleLarge)
        Text(if(ByokPolicy.FEATURE_BYOK) "Small on-device text models. Each group uses a shared 4.6 MiB detector and its own reader. Downloads install automatically; originals stay in Downloads/Hearth/models or your chosen folder." else "Small on-device text models. Import the shared detector and a reader below. This offline build does not download files or contact model publishers.")
        OcrCatalog.profiles.forEach { profile ->
            val ready=remember(revision,profile.id){models.ready(profile)}
            val pending=remember(revision,profile.id) {
                if(ByokPolicy.FEATURE_BYOK) FileDownloads(context).list().filter {
                    it.title in setOf(profile.asset.filename,OcrCatalog.detector.filename) && it.active
                } else emptyList()
            }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(profile.label,style=MaterialTheme.typography.titleMedium)
                Text("${"%.1f".format(profile.asset.bytes/1048576.0)} MiB reader · ${if(ready) "Installed" else "Needs model files"}")
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
                pending.forEach { Text("${if(it.title==OcrCatalog.detector.filename) "Detector" else "Reader"}: ${it.phase} · ${it.bytes/1048576} MiB",style=MaterialTheme.typography.bodySmall) }
                if(ByokPolicy.FEATURE_BYOK) TextButton(onClick={ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://huggingface.co/PaddlePaddle/${profile.asset.repository}"))) }) { Text("Publisher & license") }
            } }
        }
        OutlinedButton(onClick={importer.launch(arrayOf("*/*"))},enabled=!importing) { Text(if(importing) "Verifying model…" else "Import downloaded ONNX file") }
        Text("Import the detector and reader files from the linked exports. File names do not matter: Hearth identifies and verifies the exact model bytes. The matching dictionaries are included.",style=MaterialTheme.typography.bodySmall)
        if(ByokPolicy.FEATURE_BYOK) TextButton(onClick=onDownloads) { Text("Downloads & folder settings") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
