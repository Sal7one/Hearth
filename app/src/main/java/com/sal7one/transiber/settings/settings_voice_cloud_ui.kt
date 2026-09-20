package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsVoiceCloudUi(language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onForgot: ()->Unit) {
    val uiText = rememberUiText()

    if (!ByokPolicy.FEATURE_BYOK) return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val prefs=VoiceSettings.prefs(context)
    var endpoint by remember {mutableStateOf(prefs.getString("endpoint","").orEmpty())};var key by remember {mutableStateOf("")};var working by remember {mutableStateOf(false)}
    var capabilities by remember {mutableStateOf(runCatching{VoiceSettings.remote(context).capabilities}.getOrNull())}
    var selected by remember {mutableStateOf(prefs.getString("remote-voice","").orEmpty())}
    val client=remember {RemoteVoiceClient()};DisposableEffect(Unit){onDispose{client.close()}}
    val matchesSavedEndpoint = RemoteVoiceProtocol.sameEndpoint(endpoint, prefs.getString("endpoint", "").orEmpty())
    val dirty = !matchesSavedEndpoint || key.isNotBlank()
    Text(uiText(UiR.string.ui_text_is_sent_to_your_https_voice_server_chatterbox_qwen3_tts_and_b26fd))
    OutlinedTextField(endpoint,{endpoint=it},enabled=!working,label={Text(uiText(UiR.string.ui_hearth_voice_api_base_url_https_d4d7b))},modifier=Modifier.fillMaxWidth().padding(top=2.dp),singleLine=true,textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr))
    OutlinedTextField(key,{key=it},enabled=!working,label={Text(if(matchesSavedEndpoint && VoiceSettings.hasKey(context))uiText(UiR.string.ui_replace_saved_api_key_c2dc3) else uiText(UiR.string.ui_api_key_if_required_49a3c))},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().padding(top=2.dp),singleLine=true,textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr))
    Button(enabled=!working,onClick={
        val nextEndpoint=endpoint.trim()
        val replacement=key.takeIf(String::isNotBlank) ?: if(matchesSavedEndpoint) null else ""
        working=true;onError(null)
        scope.launch {
            try {
                val secret=replacement ?: VoiceSettings.readKey(context)
                val raw=client.execute(RemoteVoiceProtocol.request(nextEndpoint,secret),secret).toString(Charsets.UTF_8)
                val found=RemoteVoiceProtocol.capabilities(raw)
                VoiceSettings.saveRemote(context,nextEndpoint,replacement,raw)
                capabilities=found;key=""
            } catch(e: CancellationException){throw e}
            catch(e: Exception){onError(e.message ?: e.toString())}
            finally{working=false}
        }
    }){Text(if(working)uiText(UiR.string.ui_checking_820d6) else uiText(UiR.string.ui_save_check_voices_f25ab))}
    if(dirty) Text(uiText(UiR.string.ui_check_this_connection_to_load_its_languages_and_voices_701d4),style=MaterialTheme.typography.bodySmall)
    capabilities?.takeUnless {dirty}?.let {caps ->
        LaunchedEffect(caps){if(language !in caps.languages)onLanguage(caps.languages.first());if(selected !in caps.voices)selected=caps.voices.first()}
        Text(uiText(UiR.string.ui_1_s_2_s_languages_242cf, caps.label, caps.languages.size))
        VoiceMenu(uiText(UiR.string.ui_preview_language_fe73d),language,caps.languages.sorted().map {it to LanguageCatalog.option(it).nativeName},onLanguage)
        Text(caps.languages.sorted().joinToString {LanguageCatalog.option(it).nativeName},style=MaterialTheme.typography.bodySmall)
        VoiceMenu(uiText(UiR.string.ui_server_voice_ea31b),selected,caps.voices.map {it to it}){selected=it;prefs.edit().putString("remote-voice",it).apply()}
    }
    Text(uiText(UiR.string.ui_supported_server_models_1b8a8),style=MaterialTheme.typography.titleMedium)
    Text(uiText(UiR.string.ui_these_run_on_your_server_set_up_the_included_hearth_voice_bridge_f6248),style=MaterialTheme.typography.bodySmall)
    VoiceModelCard("Chatterbox Multilingual V3",uiText(UiR.string.ui_self_hosted_python_80a0e),uiText(UiR.string.ui_23_languages_including_arabic_default_or_reference_voice_turbo_na_b5a55),"MIT","https://github.com/resemble-ai/chatterbox",uiText(UiR.string.ui_source_models_setup_e17e2),onError)
    VoiceModelCard("Qwen3-TTS 0.6B Base",uiText(UiR.string.ui_self_hosted_python_80a0e),uiText(UiR.string.ui_10_languages_no_arabic_requires_your_reference_wav_and_matching_t_ffece),"Apache-2.0","https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base",uiText(UiR.string.ui_model_card_files_setup_2f538),onError)
    VoiceModelCard("Fish Speech",uiText(UiR.string.ui_self_hosted_official_fish_server_8efdd),uiText(UiR.string.ui_languages_and_voice_ids_depend_on_the_deployed_checkpoint_and_ser_fc6d7),uiText(UiR.string.ui_fish_audio_research_license_separate_commercial_terms_14192),"https://github.com/fishaudio/fish-speech",uiText(UiR.string.ui_source_models_setup_e17e2),onError)
    TextButton(enabled=!working,onClick={VoiceSettings.forget(context);capabilities=null;onError(null);onForgot()}){Text(uiText(UiR.string.ui_forget_voice_connection_cebb3))}
}
