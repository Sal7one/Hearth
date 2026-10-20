package com.sal7one.transiber.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.hearth.tts.omnivoice.*
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*

@Composable
internal fun SettingsVoiceOmniVoiceUi(choice: VoiceChoice,save: (VoiceChoice)->Unit,language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onImport: ()->Unit,onDownloads: ()->Unit,revision: Int,onRevision: ()->Unit) {
    val text=rememberUiText();val context=LocalContext.current
    val available=remember { LocalVoiceFamilies.available(context,"omnivoice") }
    val models=remember { OmniVoiceModels(LocalVoiceFamilies.directory(context,"omnivoice")) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(revision) { ready=withContext(Dispatchers.IO) { models.ready() } }
    LaunchedEffect(Unit) { if(language !in OmniVoiceCatalog.languages)onLanguage("ar") }
    VoiceModelCard("OmniVoice",text(R.string.omnivoice_runtime),text(R.string.omnivoice_coverage),OmniVoiceCatalog.license,OmniVoiceCatalog.modelCard,onError,
        filesUrl="${OmniVoiceCatalog.source}/tree/${OmniVoiceCatalog.revision}",licenseUrl="https://huggingface.co/bosonai/higgs-audio-v2-tokenizer/blob/main/LICENSE")
    if(!available)Text(text(R.string.omnivoice_no_runtime),color=MaterialTheme.colorScheme.error)
    Text(text(R.string.omnivoice_voice_note),style=MaterialTheme.typography.bodySmall)
    VoiceMenu(text(R.string.magpie_voice),choice.voice,OmniVoiceCatalog.designs.map { id -> id to text(when(id) { "design-female" -> R.string.omnivoice_design_female; "design-male" -> R.string.omnivoice_design_male; else -> R.string.omnivoice_design_auto }) }) { save(choice.copy(voice=it)) }
    VoiceMenu(text(R.string.magpie_language),language,OmniVoiceCatalog.languages.sorted().map { it to it }) { onLanguage(it) }
    Text(text(if(ready)R.string.omnivoice_ready else R.string.omnivoice_install_note),style=MaterialTheme.typography.bodySmall)
    VoicePackageActions("omnivoice",ready,available,revision,text(R.string.omnivoice_download),text(R.string.omnivoice_import),
        onError,onImport,onDownloads,onRevision)
}
