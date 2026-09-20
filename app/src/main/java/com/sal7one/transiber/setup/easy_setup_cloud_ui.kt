package com.sal7one.transiber.setup

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EasySetupCloudUi(onDone: () -> Unit) {
    val uiText = rememberUiText()

    if(!ByokPolicy.FEATURE_BYOK)return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val links=LocalUriHandler.current
    val actions=remember {EasySetupActions(context)}
    var provider by rememberSaveable {mutableStateOf(CloudConfigStore.Provider.OPENAI)}
    var endpoint by rememberSaveable {mutableStateOf(CloudConfigStore.baseUrl(context).takeIf {CloudConfigStore.provider(context)==CloudConfigStore.Provider.CUSTOM}.orEmpty())}
    var model by rememberSaveable {mutableStateOf(CloudConfigStore.sttModel(context).takeIf {CloudConfigStore.provider(context)==CloudConfigStore.Provider.CUSTOM}.orEmpty())}
    var key by remember {mutableStateOf("")} // Never put credentials in saved instance state.
    var target by rememberSaveable {mutableStateOf("en")}
    var picker by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {target=CaptionConfigStore.config(context).first().target.languageTag.takeIf {CaptionLanguages.openAiTranslation.accepts(it)} ?: "en"}
    val base=if(provider==CloudConfigStore.Provider.CUSTOM)endpoint else provider.baseUrl
    val reuse=EasySetupPreset.canReuseKey(provider,base,CloudConfigStore.provider(context),CloudConfigStore.baseUrl(context)) && ApiKeyStore.hasOpenAiKey(context)
    Text(uiText(UiR.string.ui_connect_once_start_listening_84a85),style=MaterialTheme.typography.headlineLarge)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        EasySetupPreset.providers.forEach {p->FilterChip(selected=provider==p,enabled=!busy,
            onClick={if(provider!=p){provider=p;key="";error=null}},label={Text(if(p==CloudConfigStore.Provider.CUSTOM) uiText(UiR.string.ui_local_server_444ff) else uiText.label(p))})}
    }
    Text(if(provider==CloudConfigStore.Provider.OPENAI) uiText(UiR.string.ui_live_speech_translation_with_your_openai_key_1bb6f) else uiText(UiR.string.ui_speech_captions_add_translation_later_in_settings_6c99e))
    if(provider==CloudConfigStore.Provider.CUSTOM) {
        OutlinedTextField(endpoint,{endpoint=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr),label={Text(uiText(UiR.string.ui_https_server_url_a8d51))},placeholder={Text("https://your-server/v1")})
        OutlinedTextField(model,{model=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr),label={Text(uiText(UiR.string.ui_speech_model_id_13cb8))})
        Text(uiText(UiR.string.ui_an_openai_compatible_speech_server_audio_goes_to_this_address_0ae3c),style=MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(key,{key=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr),
        visualTransformation=PasswordVisualTransformation(),label={Text(if(provider==CloudConfigStore.Provider.CUSTOM) uiText(UiR.string.ui_api_key_optional_9d882) else uiText(UiR.string.ui_api_key_cf678))},
        placeholder={Text(if(reuse) uiText(UiR.string.ui_saved_key_will_be_used_a8143) else uiText(UiR.string.ui_paste_your_key_f74a3))})
    if(provider==CloudConfigStore.Provider.OPENAI) {
        OutlinedButton(onClick={picker=true},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
            Text(uiText(UiR.string.ui_translate_to_1_s_f0b21, uiText.languageLabel(LanguageCatalog.option(target))))
        }
        if(picker) Dialog(onDismissRequest={picker=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                LanguagePickerContent(uiText(UiR.string.ui_translate_to_a1ba6),CaptionLanguages.openAiTranslation,target,
                    {target=it;picker=false},{picker=false})
            }
        }
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(enabled=!busy,onClick={scope.launch {
        busy=true;error=null
        try { actions.applyCloud(provider,base,if(provider==CloudConfigStore.Provider.CUSTOM)model else provider.sttModel,key,target);key="";onDone() }
        catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text(if(busy) uiText(UiR.string.ui_saving_56a22) else uiText(UiR.string.ui_save_finish_88689))}
    Text(if(provider==CloudConfigStore.Provider.CUSTOM) uiText(UiR.string.ui_uses_your_server_connection_is_checked_when_you_start_bc3f2) else uiText(UiR.string.ui_uses_your_provider_account_usage_may_be_billed_by_the_provider_1916b),style=MaterialTheme.typography.bodySmall)
    when(provider) {
        CloudConfigStore.Provider.OPENAI -> Row {TextButton(onClick={links.openUri("https://platform.openai.com/api-keys")}) {Text(uiText(UiR.string.ui_get_a_key_ae0ea))};TextButton(onClick={links.openUri("https://developers.openai.com/api/docs/pricing")}) {Text(uiText(UiR.string.ui_pricing_d1e51))}}
        CloudConfigStore.Provider.OPENROUTER -> Row {TextButton(onClick={links.openUri("https://openrouter.ai/settings/keys")}) {Text(uiText(UiR.string.ui_get_a_key_ae0ea))};TextButton(onClick={links.openUri("https://openrouter.ai/models?output_modalities=transcription")}) {Text(uiText(UiR.string.ui_speech_models_d30ae))}}
        CloudConfigStore.Provider.CUSTOM -> TextButton(onClick={links.openUri("https://developers.openai.com/api/docs/guides/speech-to-text")}) {Text(uiText(UiR.string.ui_compatible_speech_api_35f3a))}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
}
