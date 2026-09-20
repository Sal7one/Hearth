package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to uiText(UiR.string.ui_android_voices_60cb9), "supertonic" to "Supertonic 3").forEach { (id, label) ->
            FilterChip(selected = localBackend == id, onClick = { save(choice.copy(backend = id)) }, label = { Text(label) })
        }
    }
        when(localBackend) {
            "system" -> {
                Text(uiText(UiR.string.ui_only_installed_offline_android_voices_are_offered_availability_co_8ecc2))
                val languages=systemVoices.map {it.locale.language}.distinct().sorted()
                LaunchedEffect(languages){if(language !in languages && languages.isNotEmpty())onLanguage(languages.first())}
                VoiceMenu(uiText(UiR.string.ui_voice_language_fc44d),language,languages.map {it to LanguageCatalog.option(it).nativeName}){onLanguage(it)}
                val list=systemVoices.filter {it.locale.language==language}
                val p=VoiceSettings.prefs(context)
                var selected by remember(language,revision){mutableStateOf(p.getString("system-voice-$language","").orEmpty())}
                VoiceMenu(uiText(UiR.string.ui_android_voice_ba5fb),selected,listOf("" to uiText(UiR.string.ui_automatic_matching_voice_c73e2))+list.map {it.name to "${it.locale.getDisplayName()} · ${it.name}"}){selected=it;p.edit().apply {if(it.isBlank())remove("system-voice-$language") else putString("system-voice-$language",it)}.apply()}
                OutlinedButton(onClick={try{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}catch(e: Exception){onError(e.message)}}){Text(uiText(UiR.string.ui_install_or_manage_android_voices_0f03f))}
            }
            "supertonic" -> {
                val ready=remember(revision,choice.voice){models.ready(choice.voice)}
                LaunchedEffect(Unit){if(language !in VoiceCatalog.languages)onLanguage("en")}
                VoiceModelCard("Supertonic 3", uiText(UiR.string.ui_on_device_onnx_runtime_cpu_5b8ad), uiText(UiR.string.ui_31_languages_including_arabic_10_voices_about_383_mib_for_all_fil_cb874), "Model: OpenRAIL-M · runtime code: MIT", "https://huggingface.co/Supertone/supertonic-3", uiText(UiR.string.ui_model_card_files_license_45e0e), onError)
                VoiceMenu(uiText(UiR.string.ui_voice_3091c),choice.voice,VoiceCatalog.voices.map {it to it}){save(choice.copy(voice=it))}
                Text(uiText(UiR.string.ui_quality_steps_1_s_8db63, choice.steps))
                Slider(choice.steps.toFloat(),{save(choice.copy(steps=it.toInt()))},valueRange=2f..12f,steps=9,modifier=Modifier.semantics {contentDescription=uiText(UiR.string.ui_supertonic_quality_steps_80187)})
                Text(uiText(UiR.string.ui_more_steps_can_improve_quality_and_take_longer_language_support_i_96b86),style=MaterialTheme.typography.bodySmall)
                Text(if(ready)uiText(UiR.string.ui_selected_engine_and_voice_installed_f2921) else uiText(UiR.string.ui_download_or_import_the_engine_and_selected_voice_f9222))
                if(ByokPolicy.FEATURE_BYOK) {
                    val active=remember(revision){FileDownloads(context).list().filter {it.active && VoiceCatalog.assets.any {a->a.filename==it.title}}}
                    if(active.isNotEmpty())OutlinedButton(onClick=onDownloads){Text(uiText(UiR.string.ui_download_progress_1_s_files_cdb9d, active.size))}
                    else if(!ready)Button(onClick={try {val dl=FileDownloads(context);(VoiceCatalog.core+VoiceCatalog.file("${choice.voice}.json")).filterNot(models::installed).forEach {dl.enqueue(DownloadSpec(it.url,it.filename),true,it.id)};onRevision()}catch(e: Exception){onError(e.message)}}){Text(uiText(UiR.string.ui_download_engine_voice_c767f))}
                }
                OutlinedButton(onClick={onImport()},enabled=!busy){Text(if(busy)uiText(UiR.string.ui_verifying_691f4) else uiText(UiR.string.ui_import_voice_files_or_zip_068a7))}
                Text(uiText(UiR.string.ui_select_the_onnx_engine_files_its_json_files_and_one_or_more_voice_36da5),style=MaterialTheme.typography.bodySmall)
                VoiceMenu(uiText(UiR.string.ui_preview_language_fe73d),language,VoiceCatalog.languages.sorted().map {it to LanguageCatalog.option(it).nativeName}){onLanguage(it)}
            }
        }
}
