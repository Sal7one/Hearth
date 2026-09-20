package com.sal7one.transiber.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.voice.*

@Composable
internal fun SettingsVoiceLocalUi(
    choice: VoiceChoice,
    localBackend: String,
    save: (VoiceChoice) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    systemVoices: List<android.speech.tts.Voice>,
    models: VoiceModels,
    revision: Int,
    onRevision: () -> Unit,
    busy: Boolean,
    onImport: () -> Unit,
    onDownloads: () -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to "Android voices", "supertonic" to "Supertonic 3").forEach { (id, label) ->
            FilterChip(selected = localBackend == id, onClick = { save(choice.copy(backend = id)) }, label = { Text(label) })
        }
    }
        when(localBackend) {
            "system" -> {
                Text("Only installed offline Android voices are offered. Availability comes from this phone's TTS engine.")
                val languages=systemVoices.map {it.locale.language}.distinct().sorted()
                LaunchedEffect(languages){if(language !in languages && languages.isNotEmpty())onLanguage(languages.first())}
                VoiceMenu("Voice language",language,languages.map {it to LanguageCatalog.option(it).nativeName}){onLanguage(it)}
                val list=systemVoices.filter {it.locale.language==language}
                val p=VoiceSettings.prefs(context)
                var selected by remember(language,revision){mutableStateOf(p.getString("system-voice-$language","").orEmpty())}
                VoiceMenu("Android voice",selected,listOf("" to "Automatic matching voice")+list.map {it.name to "${it.locale.getDisplayName()} · ${it.name}"}){selected=it;p.edit().apply {if(it.isBlank())remove("system-voice-$language") else putString("system-voice-$language",it)}.apply()}
                OutlinedButton(onClick={try{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}catch(e: Exception){onError(e.message)}}){Text("Install or manage Android voices")}
            }
            "supertonic" -> {
                val ready=remember(revision,choice.voice){models.ready(choice.voice)}
                LaunchedEffect(Unit){if(language !in VoiceCatalog.languages)onLanguage("en")}
                VoiceModelCard("Supertonic 3", "On-device · ONNX Runtime CPU", "31 languages including Arabic · 10 voices · about 383 MiB for all files. Chinese is not supported.", "Model: OpenRAIL-M · runtime code: MIT", "https://huggingface.co/Supertone/supertonic-3", "Model card, files & license", onError)
                VoiceMenu("Voice",choice.voice,VoiceCatalog.voices.map {it to it}){save(choice.copy(voice=it))}
                Text("Quality steps · ${choice.steps}")
                Slider(choice.steps.toFloat(),{save(choice.copy(steps=it.toInt()))},valueRange=2f..12f,steps=9,modifier=Modifier.semantics {contentDescription="Supertonic quality steps"})
                Text("More steps can improve quality and take longer. Language support is model-specific; Chinese is not advertised by this model.",style=MaterialTheme.typography.bodySmall)
                Text(if(ready)"Selected engine and voice installed" else "Download or import the engine and selected voice")
                if(ByokPolicy.FEATURE_BYOK) {
                    val active=remember(revision){FileDownloads(context).list().filter {it.active && VoiceCatalog.assets.any {a->a.filename==it.title}}}
                    if(active.isNotEmpty())OutlinedButton(onClick=onDownloads){Text("Download progress · ${active.size} files")}
                    else if(!ready)Button(onClick={try {val dl=FileDownloads(context);(VoiceCatalog.core+VoiceCatalog.file("${choice.voice}.json")).filterNot(models::installed).forEach {dl.enqueue(DownloadSpec(it.url,it.filename),true,it.id)};onRevision()}catch(e: Exception){onError(e.message)}}){Text("Download engine & voice")}
                }
                OutlinedButton(onClick={onImport()},enabled=!busy){Text(if(busy)"Verifying…" else "Import voice files or ZIP")}
                Text("Select the ONNX engine files, its JSON files and one or more voice styles, or a ZIP containing them. Original downloads remain in Downloads/Hearth/models.",style=MaterialTheme.typography.bodySmall)
                VoiceMenu("Preview language",language,VoiceCatalog.languages.sorted().map {it to LanguageCatalog.option(it).nativeName}){onLanguage(it)}
            }
        }
}
