package com.sal7one.transiber.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.hearth.tts.oute.*
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.ui.components.ModelStatusLabel
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*

@Composable
internal fun SettingsVoiceOuteUi(choice: VoiceChoice,save: (VoiceChoice)->Unit,language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onImport: ()->Unit,onDownloads: ()->Unit,revision: Int,onRevision: ()->Unit) {
    val text=rememberUiText();val context=LocalContext.current
    val available=remember { LocalVoiceFamilies.available(context,"oute") }
    val models=remember { OuteModels(LocalVoiceFamilies.directory(context,"oute")) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        try {
            ready=withContext(Dispatchers.IO) { models.ready() }
        }catch(e: CancellationException){throw e}catch(e: Exception){onError(e.message ?: e.toString())}
    }
    LaunchedEffect(Unit) { if(language !in OuteCatalog.languages)onLanguage("en") }
    VoiceModelCard("OuteTTS 1.0",text(R.string.oute_runtime),text(R.string.oute_coverage),OuteCatalog.license,OuteCatalog.modelCard,onError,
        filesUrl=OuteCatalog.filesUrl,licenseUrl="${OuteCatalog.modelCard}#license-information")
    if(!available)Text(text(R.string.oute_no_runtime),color=MaterialTheme.colorScheme.error)
    Text(text(R.string.oute_reference),style=MaterialTheme.typography.bodySmall)
    if(choice.voice !in OuteCatalog.voices)OutlinedButton(onClick={save(choice.copy(voice=OuteCatalog.voices.first()))}){Text(text(R.string.oute_select_reference))}
    Text(text(R.string.oute_limits),style=MaterialTheme.typography.bodySmall)
    VoiceMenu(text(R.string.magpie_language),language,OuteCatalog.languages.sorted().map { it to LanguageCatalog.option(it).nativeName }) { onLanguage(it) }
    if(ready)ModelStatusLabel(text(R.string.oute_ready)) else Text(text(R.string.oute_install),style=MaterialTheme.typography.bodySmall)
    VoicePackageActions("oute",ready,available,revision,text(R.string.oute_download),text(R.string.oute_import),
        onError,onImport,onDownloads,onRevision)
}
