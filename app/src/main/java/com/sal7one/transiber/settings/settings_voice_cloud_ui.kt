package com.sal7one.transiber.settings

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
    if (!ByokPolicy.FEATURE_BYOK) return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val prefs=VoiceSettings.prefs(context)
    var endpoint by remember {mutableStateOf(prefs.getString("endpoint","").orEmpty())};var key by remember {mutableStateOf("")};var working by remember {mutableStateOf(false)}
    var capabilities by remember {mutableStateOf(runCatching{VoiceSettings.remote(context).capabilities}.getOrNull())}
    var selected by remember {mutableStateOf(prefs.getString("remote-voice","").orEmpty())}
    val client=remember {RemoteVoiceClient()};DisposableEffect(Unit){onDispose{client.close()}}
    val matchesSavedEndpoint = RemoteVoiceProtocol.sameEndpoint(endpoint, prefs.getString("endpoint", "").orEmpty())
    val dirty = !matchesSavedEndpoint || key.isNotBlank()
    Text("Text is sent to your HTTPS voice server. Chatterbox, Qwen3-TTS and Fish Speech require a separately running server; they are not embedded Android models. Language and voice choices come from that server.")
    OutlinedTextField(endpoint,{endpoint=it},enabled=!working,label={Text("Hearth voice API base URL · HTTPS")},modifier=Modifier.fillMaxWidth().padding(top=2.dp),singleLine=true)
    OutlinedTextField(key,{key=it},enabled=!working,label={Text(if(matchesSavedEndpoint && VoiceSettings.hasKey(context))"Replace saved API key" else "API key · if required")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().padding(top=2.dp),singleLine=true)
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
    }){Text(if(working)"Checking…" else "Save & check voices")}
    if(dirty) Text("Check this connection to load its languages and voices.",style=MaterialTheme.typography.bodySmall)
    capabilities?.takeUnless {dirty}?.let {caps ->
        LaunchedEffect(caps){if(language !in caps.languages)onLanguage(caps.languages.first());if(selected !in caps.voices)selected=caps.voices.first()}
        Text("${caps.label} · ${caps.languages.size} languages")
        VoiceMenu("Preview language",language,caps.languages.sorted().map {it to LanguageCatalog.option(it).nativeName},onLanguage)
        Text(caps.languages.sorted().joinToString {LanguageCatalog.option(it).nativeName},style=MaterialTheme.typography.bodySmall)
        VoiceMenu("Server voice",selected,caps.voices.map {it to it}){selected=it;prefs.edit().putString("remote-voice",it).apply()}
    }
    Text("Supported server models",style=MaterialTheme.typography.titleMedium)
    Text("These run on your server. Set up the included Hearth voice bridge first; connecting its HTTPS address makes that engine your custom voice option.",style=MaterialTheme.typography.bodySmall)
    VoiceModelCard("Chatterbox Multilingual V3","Self-hosted · Python","23 languages including Arabic. Default or reference voice; Turbo/Nano are separate English models.","MIT","https://github.com/resemble-ai/chatterbox","Source, models & setup",onError)
    VoiceModelCard("Qwen3-TTS 0.6B Base","Self-hosted · Python","10 languages; no Arabic. Requires your reference WAV and matching transcript.","Apache-2.0","https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base","Model card, files & setup",onError)
    VoiceModelCard("Fish Speech","Self-hosted · official Fish server","Languages and voice IDs depend on the deployed checkpoint and server configuration.","Fish Audio Research License; separate commercial terms","https://github.com/fishaudio/fish-speech","Source, models & setup",onError)
    TextButton(enabled=!working,onClick={VoiceSettings.forget(context);capabilities=null;onError(null);onForgot()}){Text("Forget voice connection")}
}
