package com.sal7one.transiber.settings

import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.hearth.tts.magpie.MagpieCatalog
import com.sal7one.hearth.tts.magpie.MagpieModels
import com.sal7one.hearth.tts.magpie.MagpieSpeechEngine
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun SettingsVoiceMagpieUi(choice: VoiceChoice,save: (VoiceChoice)->Unit,language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onImport: ()->Unit,onDownloads: ()->Unit,revision: Int,onRevision: ()->Unit) {
    val text=rememberUiText()
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val runtimeAvailable=remember {MagpiePolicy.available(context)}
    val models=remember {MagpieModels(File(context.filesDir,"voice-models/magpie"))}
    var ready by remember {mutableStateOf(false)};var working by remember {mutableStateOf(false)}
    LaunchedEffect(revision){ready=withContext(Dispatchers.IO){models.ready()}}
    LaunchedEffect(Unit){if(language !in MagpieCatalog.languages)onLanguage("ar")}
    VoiceModelCard("MagpieTTS v2607",text(R.string.magpie_runtime),text(R.string.magpie_coverage,MagpieCatalog.downloadBytes/1_000_000),"NVIDIA Open Model License; Apache-2.0 native runtime",MagpieCatalog.source,onError,
        filesUrl="${MagpieCatalog.source}/tree/${MagpieCatalog.revision}",licenseUrl="https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/")
    if(!runtimeAvailable)Text(text(R.string.magpie_no_runtime),color=MaterialTheme.colorScheme.error)
    Text(text(R.string.magpie_voice_note),style=MaterialTheme.typography.bodySmall)
    VoiceMenu(text(R.string.magpie_voice),choice.voice,MagpieCatalog.speakers.map {it to it}){save(choice.copy(voice=it))}
    VoiceMenu(text(R.string.magpie_language),language,MagpieCatalog.languages.sorted().map {it to it}){onLanguage(it)}
    Text(if(ready)text(R.string.magpie_ready) else text(R.string.magpie_install_note),style=MaterialTheme.typography.bodySmall)
    if(ByokPolicy.FEATURE_BYOK) {
        Button(enabled=runtimeAvailable && !working && !ready,onClick={scope.launch {working=true;onError(null);try {
            withContext(Dispatchers.IO){MagpiePolicy.requireAllowed(context,"Magpie model download");val downloads=FileDownloads(context);VoiceCatalog.magpieAssets.filterNot {asset ->VoiceModels(File(context.filesDir,"voice-models")).installed(asset)}.forEach {downloads.enqueue(DownloadSpec(it.url,it.filename),true,it.id)}}
            MagpieTokenizerDownload.install(context);onRevision()
        }catch(e: CancellationException){throw e}catch(e: Exception){onError(e.message ?:e.toString())}finally{working=false}}}){Text(if(working)text(R.string.magpie_installing) else text(R.string.magpie_download))}
    }
    OutlinedButton(enabled=runtimeAvailable && !working,onClick=onImport){Text(text(R.string.magpie_import))}
    OutlinedButton(onClick=onDownloads){Text(text(R.string.magpie_downloads))}
}
