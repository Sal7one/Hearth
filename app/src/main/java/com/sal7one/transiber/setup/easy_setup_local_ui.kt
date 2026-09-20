package com.sal7one.transiber.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.ui.theme.glassPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

@Composable internal fun EasySetupLocalUi(onDone: () -> Unit, onDownloads: () -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val links=LocalUriHandler.current
    val actions=remember {EasySetupActions(context)}
    var status by remember {mutableStateOf<EasyLocalStatus?>(null)}
    var target by rememberSaveable {mutableStateOf<String?>(null)}
    var picker by rememberSaveable {mutableStateOf(false)}
    var requested by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {
        if(target==null)target=CaptionConfigStore.config(context).first().target.languageTag.takeIf {it in EasySetupPreset.translator.targetLanguages} ?: "en"
        while(isActive) {
            try { status=actions.localStatus() }
            catch(e: CancellationException){throw e}
            catch(e: Exception){error=e.message ?: e.toString()}
            delay(1500)
        }
    }
    LaunchedEffect(requested,status?.ready) {
        if(requested && status?.ready==true) {
            busy=true
            try { actions.applyLocal(checkNotNull(target));requested=false;onDone() }
            catch(e: CancellationException){throw e}
            catch(e: Exception){requested=false;error=e.message ?: e.toString()}
            finally {busy=false}
        }
    }
    LaunchedEffect(requested,status?.downloads) {
        if(requested)status?.downloads?.firstOrNull {it.failed &&
            ((status?.speech==null && it.title==actions.speechDownload.fileName) ||
             (status?.translation!=true && it.title==EasySetupPreset.translator.fileName))}?.let {
            requested=false;error=it.error.ifBlank {it.phase}
        }
    }
    var importKind by rememberSaveable {mutableStateOf("speech")}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri-> if(uri!=null)scope.launch {
        busy=true;error=null
        try {
            withContext(Dispatchers.IO) {
                val job=currentCoroutineContext()
                context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                    if(importKind=="translation") LocalTranslationModels(File(context.filesDir,"translation-models")).import(input,EasySetupPreset.translator) {job.ensureActive()}
                    else {
                        val store=LocalSpeechModels(File(context.filesDir,"speech-models"))
                        input.mark(4);val magic=ByteArray(4);input.read(magic);input.reset()
                        val model=if(magic[0]=='P'.code.toByte() && magic[1]=='K'.code.toByte())store.importZip(input){job.ensureActive()}
                            else store.installPublisher(input,actions.speechDownload,-System.nanoTime()){job.ensureActive()}
                        require(model.profile==EasySetupPreset.speech){"Choose the Nemotron model for Easy setup. Other models are available in Settings."}
                    }
                } ?: error("Cannot open the model file.")
            }
            status=actions.localStatus()
        } catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }}
    Text("Your phone.\nYour words.",style=MaterialTheme.typography.headlineLarge)
    Text("Speech and translation stay on this device.")
    Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        listOf(Triple("Nemotron 3.5",status?.speech!=null,actions.speechDownload.fileName),Triple("Hy-MT2 translation",status?.translation==true,EasySetupPreset.translator.fileName)).forEach {(name,installed,file)->
            val download=status?.downloads?.firstOrNull {it.title==file}
            Text(name,style=MaterialTheme.typography.titleLarge)
            Text(if(installed) "Installed ✓" else download?.let {"${it.phase} · ${it.bytes/1_048_576} MiB"} ?: "Included in this setup",style=MaterialTheme.typography.bodySmall)
            if(!installed && download?.failed==true)Text(download.error,color=MaterialTheme.colorScheme.error)
        }
    }
    target?.let {value->TextButton(enabled=!busy&&!requested,onClick={picker=true}) {Text("Translate to ${LanguageCatalog.option(value).nativeName} ▾")}}
    if(status?.ready!=true)Text(if(ByokPolicy.FEATURE_BYOK) "About 1.9 GB to download. Extra space is needed to install. Downloads stay in your chosen folder." else "Import both models to continue. This build stays offline.",style=MaterialTheme.typography.bodySmall)
    if(busy || requested || status==null)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(ByokPolicy.FEATURE_BYOK || status?.ready==true)Button(enabled=!busy && !requested && status!=null && target!=null,onClick={scope.launch {
        busy=true;error=null
        try { if(status?.ready!=true)actions.downloadMissing();status=actions.localStatus();requested=true }
        catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {
        Text(if(requested) "Installing your setup…" else if(status?.ready==true) "Use this setup" else "Download & set up")
    }
    if(requested) {
        Text("Return here after the downloads finish to complete setup.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={requested=false}) {Text("Finish later")}
    }
    if(ByokPolicy.FEATURE_BYOK)TextButton(onClick=onDownloads) {Text("Downloads & folder ↗")}
    if(!ByokPolicy.FEATURE_BYOK) {
        OutlinedButton(enabled=!busy,onClick={importKind="speech";importer.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {Text("Import Nemotron")}
        OutlinedButton(enabled=!busy,onClick={importKind="translation";importer.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {Text("Import translation model")}
    }
    if(ByokPolicy.FEATURE_BYOK)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={links.openUri("https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b")}) {Text("Nemotron ↗")}
        TextButton(onClick={links.openUri(EasySetupPreset.translator.modelCard)}) {Text("Translator ↗")}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(picker)Dialog(onDismissRequest={picker=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {LanguagePickerContent("Translate to",CaptionLanguageChoices(EasySetupPreset.translator.targetLanguages,"Hy-MT2 supported destinations"),target ?: "en",{target=it;picker=false},{picker=false})}
    }
}
