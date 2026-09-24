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
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.ui.theme.glassPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

@Composable internal fun EasySetupLocalUi(onDone: () -> Unit, onDownloads: () -> Unit) {
    val uiText = rememberUiText()

    val context=LocalContext.current;val scope=rememberCoroutineScope();val links=LocalUriHandler.current
    val actions=remember {EasySetupActions(context)}
    var status by remember {mutableStateOf<EasyLocalStatus?>(null)}
    var selectedId by rememberSaveable {mutableStateOf(if(ByokPolicy.FEATURE_BYOK) MarianPackage.pairs.first().id else EasySetupPreset.broadTranslatorId)}
    var source by rememberSaveable {mutableStateOf("en")}
    var target by rememberSaveable {mutableStateOf("ar")}
    var picker by rememberSaveable {mutableStateOf<String?>(null)}
    var requested by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val selectedPair=MarianPackage.find(selectedId)
    val selectedRoute=MarianCascade.find(selectedId)
    val selectedParts=selectedPair?.parts ?: selectedRoute?.let { it.first.parts + it.second.parts }
    val selectedSpec=if(selectedPair==null && selectedRoute==null) EasySetupPreset.broadTranslator else null
    LaunchedEffect(Unit) {
        val saved=CaptionConfigStore.config(context).first()
        val savedId=saved.localTranslationModelId
        if(savedId==EasySetupPreset.broadTranslatorId || MarianPackage.find(savedId)?.let {
                ByokPolicy.FEATURE_BYOK || MarianPackage.installed(context,it)!=null
            } == true || MarianCascade.find(savedId)?.let {
                ByokPolicy.FEATURE_BYOK || MarianCascade.installed(context,it)
            } == true) {
            selectedId=savedId
            source=saved.streamLanguage.takeIf { it in EasySetupPreset.speech.capabilities.sourceLanguageHints } ?: "en"
            target=saved.target.languageTag
            MarianPackage.find(savedId)?.let {source=it.source;target=it.target}
            MarianCascade.find(savedId)?.let {source=it.source;target=it.target}
        }
    }
    LaunchedEffect(selectedId) {
        status=null
        while(isActive) {
            try { status=actions.localStatus(selectedId) }
            catch(e: CancellationException){throw e}
            catch(e: Exception){error=e.message ?: e.toString()}
            delay(1500)
        }
    }
    LaunchedEffect(requested,status?.ready,selectedId) {
        if(requested && status?.ready==true) {
            busy=true
            try { actions.applyLocal(selectedId,source,target);requested=false;onDone() }
            catch(e: CancellationException){throw e}
            catch(e: Exception){requested=false;error=e.message ?: e.toString()}
            finally {busy=false}
        }
    }
    LaunchedEffect(requested,status?.downloads,selectedId) {
        if(requested)status?.downloads?.firstOrNull {it.failed &&
            ((status?.speech==null && it.title==actions.speechDownload.fileName) ||
             (status?.translation!=true && (selectedParts?.any { part -> part.id==it.modelId } == true ||
                 it.title==selectedSpec?.fileName)))}?.let {
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
                    if(importKind=="translation") LocalTranslationModels(File(context.filesDir,"translation-models")).import(input,EasySetupPreset.broadTranslator) {job.ensureActive()}
                    else {
                        val store=LocalSpeechModels(File(context.filesDir,"speech-models"))
                        input.mark(4);val magic=ByteArray(4);input.read(magic);input.reset()
                        val model=if(magic[0]=='P'.code.toByte() && magic[1]=='K'.code.toByte())store.importZip(input){job.ensureActive()}
                            else store.installPublisher(input,actions.speechDownload,-System.nanoTime()){job.ensureActive()}
                        require(model.profile==EasySetupPreset.speech){uiText(UiR.string.ui_choose_the_nemotron_model_for_easy_setup_other_models_are_availab_b3137)}
                    }
                } ?: error(uiText(UiR.string.ui_cannot_open_the_model_file_510e7))
            }
            status=actions.localStatus(selectedId)
        } catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }}
    Text(uiText(UiR.string.ui_your_phone_your_words_8fa46),style=MaterialTheme.typography.headlineLarge)
    Text(uiText(UiR.string.ui_speech_and_translation_stay_on_this_device_0bdc1))
    Text(uiText(UiR.string.easy_setup_choose_pair),style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        val choices=EasySetupPreset.fastPairs.filter { ByokPolicy.FEATURE_BYOK || MarianPackage.installed(context,it)!=null }
        choices.forEach { pair ->
            FilterChip(selected=selectedId==pair.id,enabled=!busy&&!requested,onClick={
                selectedId=pair.id;source=pair.source;target=pair.target;error=null
            },label={Text("${TranslationLanguages.label(pair.source)} → ${TranslationLanguages.label(pair.target)}")})
        }
        EasySetupPreset.fastRoutes.filter { ByokPolicy.FEATURE_BYOK || MarianCascade.installed(context,it) }.forEach { route ->
            FilterChip(selected=selectedId==route.id,enabled=!busy&&!requested,onClick={
                selectedId=route.id;source=route.source;target=route.target;error=null
            },label={Text("${TranslationLanguages.label(route.source)} → English → ${TranslationLanguages.label(route.target)}")})
        }
        FilterChip(selected=selectedId==EasySetupPreset.broadTranslatorId,enabled=!busy&&!requested,
            onClick={selectedId=EasySetupPreset.broadTranslatorId;error=null},
            label={Text(uiText(UiR.string.easy_setup_broad_languages))})
    }
    Text(if(selectedRoute!=null) uiText(UiR.string.model_marian_via_english_warning)
        else if(selectedPair!=null) uiText(UiR.string.easy_setup_fast_pair_note)
        else uiText(UiR.string.easy_setup_broad_note),style=MaterialTheme.typography.bodySmall)
    Column(Modifier.fillMaxWidth().glassPanel().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        val speechRecord=status?.downloads?.firstOrNull {it.title==actions.speechDownload.fileName}
        Text("Nemotron 3.5",style=MaterialTheme.typography.titleLarge)
        Text(if(status?.speech!=null)uiText(UiR.string.ui_installed_e0586) else speechRecord?.let {"${it.phase} · ${it.bytes/1_048_576} MiB"}
            ?:uiText(UiR.string.ui_included_in_this_setup_4f499),style=MaterialTheme.typography.bodySmall)
        speechRecord?.takeIf {it.failed}?.let {Text(it.error,color=MaterialTheme.colorScheme.error)}
        Text(selectedPair?.let {"Marian / OPUS-MT · ${TranslationLanguages.label(it.source)} → ${TranslationLanguages.label(it.target)}"}
            ?: selectedRoute?.let {"Marian / OPUS-MT · ${TranslationLanguages.label(it.source)} → English → ${TranslationLanguages.label(it.target)}"}
            ?:uiText(UiR.string.ui_hy_mt2_translation_389c9),style=MaterialTheme.typography.titleLarge)
        val translationRecords=if(selectedParts!=null)selectedParts.mapNotNull {part->status?.downloads?.firstOrNull {it.modelId==part.id}}
            else listOfNotNull(status?.downloads?.firstOrNull {it.title==selectedSpec?.fileName})
        Text(if(status?.translation==true)uiText(UiR.string.ui_installed_e0586)
            else if(translationRecords.isNotEmpty()) "${translationRecords.count {it.complete}}/${selectedParts?.size ?: 1} · ${translationRecords.sumOf {it.bytes}/1_048_576} MiB"
            else uiText(UiR.string.ui_included_in_this_setup_4f499),style=MaterialTheme.typography.bodySmall)
        translationRecords.firstOrNull {it.failed}?.let {Text(it.error,color=MaterialTheme.colorScheme.error)}
    }
    if(selectedParts==null) {
        TextButton(enabled=!busy&&!requested,onClick={picker="source"}) {Text(uiText(UiR.string.easy_setup_spoken_language,LanguageCatalog.option(source).nativeName))}
        TextButton(enabled=!busy&&!requested,onClick={picker="target"}) {Text(uiText(UiR.string.ui_translate_to_1_s_f0b21,LanguageCatalog.option(target).nativeName))}
        if(source==target)Text(uiText(UiR.string.easy_setup_choose_different_languages),color=MaterialTheme.colorScheme.error)
    }
    if(status?.ready!=true)Text(if(ByokPolicy.FEATURE_BYOK)
        uiText(UiR.string.easy_setup_download_size,((selectedPair?.downloadBytes ?: selectedRoute?.downloadBytes ?: EasySetupPreset.broadTranslator.bytes)+actions.speechDownload.bytes)/1_048_576)
        else uiText(UiR.string.ui_import_both_models_to_continue_this_build_stays_offline_b2b39),style=MaterialTheme.typography.bodySmall)
    if(busy || requested || status==null)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(ByokPolicy.FEATURE_BYOK || status?.ready==true)Button(enabled=!busy && !requested && status!=null && source!=target,onClick={scope.launch {
        busy=true;error=null
        try { if(status?.ready!=true)actions.downloadMissing(selectedId);status=actions.localStatus(selectedId);requested=true }
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
        TextButton(onClick={links.openUri(selectedPair?.modelCard ?: selectedRoute?.first?.modelCard ?: EasySetupPreset.broadTranslator.modelCard)}) {Text(uiText(UiR.string.ui_translator_79f5c))}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(picker!=null)Dialog(onDismissRequest={picker=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val spoken=picker=="source"
        val choices=if(spoken)EasySetupPreset.broadTranslator.sourceLanguages.intersect(EasySetupPreset.speech.capabilities.sourceLanguageHints)
            else EasySetupPreset.broadTranslator.targetLanguages
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {LanguagePickerContent(
            if(spoken)uiText(UiR.string.easy_setup_spoken_title) else uiText(UiR.string.ui_translate_to_a1ba6),
            CaptionLanguageChoices(choices,uiText(UiR.string.ui_hy_mt2_supported_destinations_fad6a)),
            if(spoken)source else target,{if(spoken)source=it else target=it;picker=null},{picker=null})}
    }
}
