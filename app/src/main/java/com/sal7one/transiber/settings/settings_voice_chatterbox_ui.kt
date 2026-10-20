package com.sal7one.transiber.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.hearth.tts.chatterbox.*
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*

@Composable
internal fun SettingsVoiceChatterboxUi(choice: VoiceChoice,save: (VoiceChoice)->Unit,language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onImport: ()->Unit,onDownloads: ()->Unit,revision: Int,onRevision: ()->Unit) {
    val text=rememberUiText();val context=LocalContext.current
    val available=remember {LocalVoiceFamilies.available(context,"chatterbox-v3")}
    val models=remember {ChatterboxModels(LocalVoiceFamilies.directory(context,"chatterbox-v3"))}
    var ready by remember {mutableStateOf(false)}
    LaunchedEffect(revision) {ready=withContext(Dispatchers.IO){models.ready()}}
    LaunchedEffect(Unit) {if(language !in ChatterboxCatalog.languages)onLanguage("ar")}
    VoiceModelCard("Chatterbox Multilingual V3",text(R.string.chatterbox_runtime),text(R.string.chatterbox_coverage),ChatterboxCatalog.license,ChatterboxCatalog.source,onError,
        filesUrl="${ChatterboxCatalog.source}/tree/${ChatterboxCatalog.revision}",licenseUrl="${ChatterboxCatalog.source}/blob/${ChatterboxCatalog.revision}/LICENSE")
    if(!available)Text(text(R.string.chatterbox_no_runtime),color=MaterialTheme.colorScheme.error)
    Text(text(R.string.chatterbox_reference),style=MaterialTheme.typography.bodySmall)
    if(choice.voice !in ChatterboxCatalog.voices)OutlinedButton(onClick={save(choice.copy(voice="default"))}){Text(text(R.string.oute_select_reference))}
    VoiceMenu(text(R.string.magpie_language),language,ChatterboxCatalog.languages.sorted().map{it to it}){onLanguage(it)}
    Text(text(if(ready)R.string.chatterbox_ready else R.string.chatterbox_install),style=MaterialTheme.typography.bodySmall)
    VoicePackageActions("chatterbox-v3",ready,available,revision,text(R.string.chatterbox_download),text(R.string.chatterbox_import),onError,onImport,onDownloads,onRevision)
}
