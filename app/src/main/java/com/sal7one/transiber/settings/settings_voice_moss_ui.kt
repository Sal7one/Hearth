package com.sal7one.transiber.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.hearth.tts.moss.*
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*

@Composable
internal fun SettingsVoiceMossUi(choice: VoiceChoice,save: (VoiceChoice)->Unit,language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onImport: ()->Unit,onDownloads: ()->Unit,revision: Int,onRevision: ()->Unit) {
    val text=rememberUiText();val context=LocalContext.current
    val available=remember { LocalVoiceFamilies.available(context,"moss-nano") }
    val models=remember { MossModels(LocalVoiceFamilies.directory(context,"moss-nano")) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(revision) { ready=withContext(Dispatchers.IO) { models.ready() } }
    LaunchedEffect(Unit) { if(language !in MossCatalog.languages)onLanguage("ar") }
    VoiceModelCard("MOSS-TTS Nano",text(R.string.moss_nano_runtime),text(R.string.moss_nano_coverage),MossCatalog.license,MossCatalog.source,onError,
        filesUrl="${MossCatalog.source}/tree/${MossCatalog.revision}",licenseUrl="https://github.com/OpenMOSS/MOSS-TTS-Nano/blob/8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb/LICENSE")
    if(!available)Text(text(R.string.moss_nano_no_runtime),color=MaterialTheme.colorScheme.error)
    Text(text(R.string.moss_nano_voice_note),style=MaterialTheme.typography.bodySmall)
    VoiceMenu(text(R.string.magpie_voice),choice.voice,MossCatalog.voices.map { it to it }) { save(choice.copy(voice=it)) }
    VoiceMenu(text(R.string.magpie_language),language,MossCatalog.languages.sorted().map { it to it }) { onLanguage(it) }
    Text(text(if(ready)R.string.moss_nano_ready else R.string.moss_nano_install_note),style=MaterialTheme.typography.bodySmall)
    VoicePackageActions("moss-nano",ready,available,revision,text(R.string.moss_nano_download),text(R.string.moss_nano_import),
        onError,onImport,onDownloads,onRevision)
}
