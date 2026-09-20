package com.sal7one.transiber.setup

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

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
                        require(model.profile==EasySetupPreset.speech){uiText(UiR.string.ui_choose_the_nemotron_model_for_easy_setup_other_models_are_availab_b3137)}
                    }
                } ?: error(uiText(UiR.string.ui_cannot_open_the_model_file_510e7))
            }
            status=actions.localStatus()
        } catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }}
    Text(uiText(UiR.string.ui_your_phone_your_words_8fa46),style=MaterialTheme.typography.headlineLarge)
    Text(uiText(UiR.string.ui_speech_and_translation_stay_on_this_device_0bdc1))
    Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        listOf(Triple("Nemotron 3.5",status?.speech!=null,actions.speechDownload.fileName),Triple(uiText(UiR.string.ui_hy_mt2_translation_389c9),status?.translation==true,EasySetupPreset.translator.fileName)).forEach {(name,installed,file)->
            val download=status?.downloads?.firstOrNull {it.title==file}
            Text(name,style=MaterialTheme.typography.titleLarge)
            Text(if(installed) uiText(UiR.string.ui_installed_e0586) else download?.let {"${it.phase} · ${it.bytes/1_048_576} MiB"} ?: uiText(UiR.string.ui_included_in_this_setup_4f499),style=MaterialTheme.typography.bodySmall)
            if(!installed && download?.failed==true)Text(download.error,color=MaterialTheme.colorScheme.error)
        }
    }
    target?.let {value->TextButton(enabled=!busy&&!requested,onClick={picker=true}) {Text(uiText(UiR.string.ui_translate_to_1_s_f0b21, LanguageCatalog.option(value).nativeName))}}
    if(status?.ready!=true)Text(if(ByokPolicy.FEATURE_BYOK) uiText(UiR.string.ui_about_1_9_gb_to_download_extra_space_is_needed_to_install_downloa_c2bcf) else uiText(UiR.string.ui_import_both_models_to_continue_this_build_stays_offline_b2b39),style=MaterialTheme.typography.bodySmall)
    if(busy || requested || status==null)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(ByokPolicy.FEATURE_BYOK || status?.ready==true)Button(enabled=!busy && !requested && status!=null && target!=null,onClick={scope.launch {
        busy=true;error=null
        try { if(status?.ready!=true)actions.downloadMissing();status=actions.localStatus();requested=true }
        catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {
        Text(if(requested) uiText(UiR.string.ui_installing_your_setup_cc124) else if(status?.ready==true) uiText(UiR.string.ui_use_this_setup_83b35) else uiText(UiR.string.ui_download_set_up_87437))
    }
    if(requested) {
        Text(uiText(UiR.string.ui_return_here_after_the_downloads_finish_to_complete_setup_0e83b),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={requested=false}) {Text(uiText(UiR.string.ui_finish_later_4d8bc))}
    }
    if(ByokPolicy.FEATURE_BYOK)TextButton(onClick=onDownloads) {Text(uiText(UiR.string.ui_downloads_folder_3d214))}
    if(!ByokPolicy.FEATURE_BYOK) {
        OutlinedButton(enabled=!busy,onClick={importKind="speech";importer.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {Text(uiText(UiR.string.ui_import_nemotron_f0905))}
        OutlinedButton(enabled=!busy,onClick={importKind="translation";importer.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {Text(uiText(UiR.string.ui_import_translation_model_6c131))}
    }
    if(ByokPolicy.FEATURE_BYOK)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={links.openUri("https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b")}) {Text("Nemotron ↗")}
        TextButton(onClick={links.openUri(EasySetupPreset.translator.modelCard)}) {Text(uiText(UiR.string.ui_translator_79f5c))}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(picker)Dialog(onDismissRequest={picker=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {LanguagePickerContent(uiText(UiR.string.ui_translate_to_a1ba6),CaptionLanguageChoices(EasySetupPreset.translator.targetLanguages,uiText(UiR.string.ui_hy_mt2_supported_destinations_fad6a)),target ?: "en",{target=it;picker=false},{picker=false})}
    }
}
