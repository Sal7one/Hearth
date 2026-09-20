package com.sal7one.transiber.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EasySetupCloudUi(onDone: () -> Unit) {
    if(!ByokPolicy.FEATURE_BYOK)return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val links=LocalUriHandler.current
    val actions=remember {EasySetupActions(context)}
    var provider by rememberSaveable {mutableStateOf(CloudConfigStore.Provider.OPENAI)}
    var endpoint by rememberSaveable {mutableStateOf(CloudConfigStore.baseUrl(context).takeIf {CloudConfigStore.provider(context)==CloudConfigStore.Provider.CUSTOM}.orEmpty())}
    var model by rememberSaveable {mutableStateOf(CloudConfigStore.sttModel(context).takeIf {CloudConfigStore.provider(context)==CloudConfigStore.Provider.CUSTOM}.orEmpty())}
    var key by remember {mutableStateOf("")} // Never put credentials in saved instance state.
    var target by rememberSaveable {mutableStateOf("en")}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {target=CaptionConfigStore.config(context).first().target.languageTag.takeIf {it in EasySetupPreset.openAiTargets} ?: "en"}
    val base=if(provider==CloudConfigStore.Provider.CUSTOM)endpoint else provider.baseUrl
    val reuse=EasySetupPreset.canReuseKey(provider,base,CloudConfigStore.provider(context),CloudConfigStore.baseUrl(context)) && ApiKeyStore.hasOpenAiKey(context)
    Text("Connect once.\nStart listening.",style=MaterialTheme.typography.headlineLarge)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        EasySetupPreset.providers.forEach {p->FilterChip(selected=provider==p,enabled=!busy,
            onClick={if(provider!=p){provider=p;key="";error=null}},label={Text(if(p==CloudConfigStore.Provider.CUSTOM) "Local server" else p.label)})}
    }
    Text(if(provider==CloudConfigStore.Provider.OPENAI) "Live speech translation with your OpenAI key." else "Speech captions. Add translation later in Settings.")
    if(provider==CloudConfigStore.Provider.CUSTOM) {
        OutlinedTextField(endpoint,{endpoint=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("HTTPS server URL")},placeholder={Text("https://your-server/v1")})
        OutlinedTextField(model,{model=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Speech model ID")})
        Text("An OpenAI-compatible speech server. Audio goes to this address.",style=MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(key,{key=it},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
        visualTransformation=PasswordVisualTransformation(),label={Text(if(provider==CloudConfigStore.Provider.CUSTOM) "API key · optional" else "API key")},
        placeholder={Text(if(reuse) "Saved key will be used" else "Paste your key")})
    if(provider==CloudConfigStore.Provider.OPENAI) {
        Text("Translate to",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {EasySetupPreset.openAiTargets.forEach {code->
            FilterChip(selected=target==code,enabled=!busy,onClick={target=code},label={Text(LanguageCatalog.option(code).nativeName)})
        }}
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(enabled=!busy,onClick={scope.launch {
        busy=true;error=null
        try { actions.applyCloud(provider,base,if(provider==CloudConfigStore.Provider.CUSTOM)model else provider.sttModel,key,target);key="";onDone() }
        catch(e: CancellationException){throw e}
        catch(e: Exception){error=e.message ?: e.toString()}
        finally {busy=false}
    }},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text(if(busy) "Saving…" else "Save & finish")}
    Text(if(provider==CloudConfigStore.Provider.CUSTOM) "Uses your server. Connection is checked when you start." else "Uses your provider account. Usage may be billed by the provider.",style=MaterialTheme.typography.bodySmall)
    when(provider) {
        CloudConfigStore.Provider.OPENAI -> Row {TextButton(onClick={links.openUri("https://platform.openai.com/api-keys")}) {Text("Get a key ↗")};TextButton(onClick={links.openUri("https://developers.openai.com/api/docs/pricing")}) {Text("Pricing ↗")}}
        CloudConfigStore.Provider.OPENROUTER -> Row {TextButton(onClick={links.openUri("https://openrouter.ai/settings/keys")}) {Text("Get a key ↗")};TextButton(onClick={links.openUri("https://openrouter.ai/models?output_modalities=transcription")}) {Text("Speech models ↗")}}
        CloudConfigStore.Provider.CUSTOM -> TextButton(onClick={links.openUri("https://developers.openai.com/api/docs/guides/speech-to-text")}) {Text("Compatible speech API ↗")}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
}
