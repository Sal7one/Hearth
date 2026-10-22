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
import com.sal7one.hearth.tts.ming.MingCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.voice.*
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.rememberSetupMode
import com.sal7one.transiber.ui.components.*
import kotlinx.coroutines.*
import androidx.compose.runtime.saveable.rememberSaveable

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
    val localModelsAllowed = com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)
    val advanced = rememberSetupMode() == SetupMode.ADVANCED
    var showMoreEngines by rememberSaveable { mutableStateOf(false) }
    val visibleFamilies = LocalVoiceFamilies.ids.filter {
        advanced || showMoreEngines || it == choice.family || it in setOf("supertonic", "magpie") ||
            (it == MingCatalog.FAMILY && MingLocalFeature.enabled(context))
    }
    val scope = rememberCoroutineScope()
    var queuing by remember { mutableStateOf(false) }
    SettingsSection content@{
        VoiceMenu(uiText(UiR.string.ui_speaker_engine_label_7f2c6),
            if (localBackend == "system") "system" else choice.family,
            listOf("system" to uiText(UiR.string.ui_android_voices_60cb9)) +
                if (localModelsAllowed) visibleFamilies.map { it to LocalVoiceFamilies.label(it) } else emptyList()) { id ->
            if (id == "system") save(choice.copy(backend = "system"))
            else save(choice.copy(backend = "supertonic", family = id,
                voice = if (choice.family == id) choice.voice else VoiceSettings.familyVoice(context, id)))
        }
        if (!advanced && localModelsAllowed) TextButton(onClick = { showMoreEngines = !showMoreEngines }) {
            Text(uiText(if (showMoreEngines) UiR.string.voice_recommended_engines else UiR.string.voice_more_engines))
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
                if (!localModelsAllowed) { LocalModelPolicyNotice(); return@content }
                if(choice.family==MingCatalog.FAMILY) {
                    SettingsVoiceMingUi(choice,save,language,onLanguage,onError,onImport,onDownloads,revision,onRevision)
                    return@content
                }
                if(choice.family=="omnivoice") {
                    SettingsVoiceOmniVoiceUi(choice,save,language,onLanguage,onError,onImport,onDownloads,revision,onRevision)
                    return@content
                }
                if(choice.family=="chatterbox-v3") {
                    SettingsVoiceChatterboxUi(choice,save,language,onLanguage,onError,onImport,onDownloads,revision,onRevision)
                    return@content
                }
                if(choice.family=="oute") {
                    SettingsVoiceOuteUi(choice,save,language,onLanguage,onError,onImport,onDownloads,revision,onRevision)
                    return@content
                }
                if(choice.family=="moss-nano") {
                    SettingsVoiceMossUi(choice,save,language,onLanguage,onError,onImport,onDownloads,revision,onRevision)
                    return@content
                }
                if(choice.family=="magpie") {
                    SettingsVoiceMagpieUi(choice,save,language,onLanguage,onError,onImport,onDownloads,revision,onRevision)
                    return@content
                }
                var ready by remember { mutableStateOf(false) }
                var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
                var tuning by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(revision, choice.voice) {
                    try {
                        val snapshot = withContext(Dispatchers.IO) {
                            models.ready(choice.voice) to if (ByokPolicy.FEATURE_BYOK) FileDownloads(context).list() else emptyList()
                        }
                        ready = snapshot.first; records = snapshot.second
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { onError(e.message ?: e.toString()) }
                }
                LaunchedEffect(Unit) { if (language !in VoiceCatalog.languages) onLanguage("en") }
                val required = VoiceCatalog.core + VoiceCatalog.file("${choice.voice}.json")
                VoiceModelCard("Supertonic 3", uiText(UiR.string.ui_on_device_onnx_runtime_cpu_5b8ad),
                    uiText(UiR.string.explorer_voice_coverage, VoiceCatalog.languages.size, VoiceCatalog.voices.size,
                        required.sumOf { it.bytes } / 1_048_576),
                    uiText(UiR.string.explorer_voice_license, VoiceCatalog.license), VoiceCatalog.modelCard, onError,
                    filesUrl = VoiceCatalog.filesUrl, licenseUrl = VoiceCatalog.licenseUrl)
                VoiceMenu(uiText(UiR.string.ui_voice_3091c), choice.voice, VoiceCatalog.voices.map { it to it }) { save(choice.copy(voice = it)) }
                if (ready) ModelStatusLabel(uiText(UiR.string.ui_selected_engine_and_voice_installed_f2921))
                else Text(uiText(UiR.string.ui_download_or_import_the_engine_and_selected_voice_f9222), style = MaterialTheme.typography.bodySmall)
                if (rememberSetupMode() == SetupMode.ADVANCED) {
                    TextButton(onClick = { tuning = !tuning }) { Text(uiText(UiR.string.explorer_voice_tuning)) }
                    if (tuning) {
                        Text(uiText(UiR.string.ui_quality_steps_1_s_8db63, choice.steps))
                        Slider(choice.steps.toFloat(), { save(choice.copy(steps = it.toInt())) }, valueRange = 2f..12f, steps = 9,
                            modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_supertonic_quality_steps_80187) })
                        Text(uiText(UiR.string.ui_more_steps_can_improve_quality_and_take_longer_language_support_i_96b86), style = MaterialTheme.typography.bodySmall)
                        Text(uiText(UiR.string.model_browser_revision, VoiceCatalog.revision), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (ByokPolicy.FEATURE_BYOK) {
                    val relevant = records.filter { row -> required.any { it.id == row.modelId || it.filename == row.title } }
                    val active = relevant.filter { it.active }
                    val failure = relevant.firstOrNull { it.failed && relevant.none { other -> other.modelId == it.modelId && (other.active || other.complete) } }
                    if (!ready) failure?.let { Text(it.error.ifBlank {
                        uiText(UiR.string.ui_download_failed_reason_1_s_8bb99, it.reason)
                    }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (active.isNotEmpty()) OutlinedButton(onClick = onDownloads) { Text(uiText(UiR.string.ui_download_progress_1_s_files_cdb9d, active.size)) }
                    else if (!ready) Button(enabled = !busy && !queuing, onClick = {
                        scope.launch {
                            queuing = true; onError(null)
                            try {
                                withContext(Dispatchers.IO) {
                                    val dl = FileDownloads(context)
                                    required.filterNot(models::installed).forEach { dl.enqueue(DownloadSpec(it.url, it.filename), true, it.id) }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { onError(e.message ?: e.toString()) }
                            finally { queuing = false; onRevision() }
                        }
                    }) { Text(uiText(if (failure != null) UiR.string.ui_retry_download_install_e5444 else UiR.string.ui_download_engine_voice_c767f)) }
                }
                OutlinedButton(onClick={onImport()},enabled=!busy && !queuing){Text(if(busy)uiText(UiR.string.ui_verifying_691f4) else uiText(UiR.string.ui_import_voice_files_or_zip_068a7))}
                Text(uiText(UiR.string.ui_select_the_onnx_engine_files_its_json_files_and_one_or_more_voice_36da5),style=MaterialTheme.typography.bodySmall)
                VoiceMenu(uiText(UiR.string.ui_preview_language_fe73d),language,VoiceCatalog.languages.sorted().map {it to LanguageCatalog.option(it).nativeName}){onLanguage(it)}
            }
        }
    }
}
