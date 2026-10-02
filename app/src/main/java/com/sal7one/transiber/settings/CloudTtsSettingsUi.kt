package com.sal7one.transiber.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.i18n.rememberUiText
import kotlinx.coroutines.*

/** Provider setup never selects an STT engine or enables read-aloud automatically. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CloudTtsSettingsUi() {
    val text = rememberUiText(); val context = LocalContext.current; val scope = rememberCoroutineScope()
    if (!ByokPolicy.FEATURE_BYOK) { Text(text(R.string.cloud_voice_offline)); return }
    var error by remember { mutableStateOf<String?>(null) }
    val initial = remember { runCatching { CloudVoiceStore.config(context) }.getOrElse { error = it.message; CloudVoiceConfig() } }
    var config by remember { mutableStateOf(initial) }; var saved by remember { mutableStateOf(initial) }
    var key by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(CloudVoiceStore.hasKey(context, initial)) }
    var models by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var voices by remember { mutableStateOf(emptyList<CloudVoiceProtocol.Voice>()) }
    var nextPage by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var sample by rememberSaveable { mutableStateOf("Hello. مرحباً بكم في هيرث. 欢迎使用 Hearth。") }
    var notice by remember { mutableStateOf<String?>(null) }
    var client by remember { mutableStateOf<CloudVoiceClient?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var speaker by remember { mutableStateOf<CaptionSpeaker?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewSequence by remember { mutableLongStateOf(0L) }
    var workSequence by remember { mutableLongStateOf(0L) }
    fun stopPreview() { previewSequence++; speaker?.stop(); previewing = false }
    fun stop() { workSequence++; job?.cancel(); client?.cancel(); client = null; job = null; stopPreview(); busy = false }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop(); speaker?.release() }
    }
    fun launchWork(block: suspend () -> Unit) {
        val generation = ++workSequence
        busy = true; error = null; notice = null
        job = scope.launch {
            try { block() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if (generation == workSequence) error = e.message ?: e.toString() }
            finally { if (generation == workSequence) { busy = false; client?.cancel(); client = null; job = null } }
        }
    }
    val dirty = config != saved || key.isNotBlank()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CloudVoiceProvider.entries.forEach { provider ->
            FilterChip(selected = config.provider == provider, enabled = !busy, onClick = {
                stop(); speaker?.release(); speaker = null
                config = CloudVoiceStore.forProvider(context, provider)
                key = ""; models = emptyList(); voices = emptyList(); nextPage = null; error = null; notice = null
                hasKey = runCatching { CloudVoiceStore.hasKey(context, config) }.getOrDefault(false)
            }, label = { Text(provider.label) })
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(config.provider.label, style = MaterialTheme.typography.titleLarge)
            Text(text(when(config.provider) {
                CloudVoiceProvider.OPENAI -> R.string.cloud_voice_openai_info
                CloudVoiceProvider.OPENROUTER -> R.string.cloud_voice_router_info
                CloudVoiceProvider.GEMINI -> R.string.cloud_voice_google_info
                CloudVoiceProvider.ELEVENLABS -> R.string.cloud_voice_eleven_info
                CloudVoiceProvider.CUSTOM -> R.string.cloud_voice_custom_info
            }), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (config.provider.keyUrl.isNotBlank()) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.provider.keyUrl))) }.onFailure { error = it.message }
                }) { Text(text(R.string.cloud_voice_get_key)) }
                TextButton(onClick = {
                    val url = if (config.provider == CloudVoiceProvider.OPENROUTER && config.model.matches(Regex("[A-Za-z0-9_./:-]+")))
                        "https://openrouter.ai/${config.model}" else config.provider.docsUrl
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { error = it.message }
                }) { Text(text(R.string.cloud_voice_guide)) }
            }
        }
    }
    if (config.provider == CloudVoiceProvider.CUSTOM) OutlinedTextField(config.endpoint, {
        config = config.copy(endpoint = it); hasKey = runCatching { CloudVoiceStore.hasKey(context, config) }.getOrDefault(false)
    }, enabled = !busy, label = { Text(text(R.string.ui_base_url_1dbd6)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(key, { key = it }, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
        label = { Text(text(if (hasKey) R.string.ui_replace_saved_api_key_c2dc3 else R.string.cloud_voice_api_key)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    val modelOptions = (models + CloudVoiceCatalog.models(config.provider).map { it to it } + listOf(config.model to config.model))
        .filter { it.first.isNotBlank() }.distinctBy { it.first }
    VoiceMenu(text(R.string.cloud_voice_model), config.model, modelOptions) { model ->
        stopPreview(); config = config.copy(model = model, instructions = "", speed = 1f, femaleVoice = "", maleVoice = "", providerDefaultVoice = false)
        val choices = CloudVoiceCatalog.voices(config)
        config = config.copy(voice = config.voice.takeIf { it in choices } ?: choices.firstOrNull().orEmpty())
    }
    if (config.provider in setOf(CloudVoiceProvider.OPENROUTER, CloudVoiceProvider.ELEVENLABS)) OutlinedButton(enabled = !busy, onClick = {
        val snapshot = config
        launchWork {
            val c = CloudVoiceClient(); client = c
            models = withContext(Dispatchers.IO) {
                val secret = key.takeIf { it.isNotBlank() } ?: CloudVoiceStore.key(context, snapshot)
                CloudVoiceProtocol.models(snapshot.provider, c.catalog(CloudVoiceProtocol.modelsRequest(snapshot), secret))
            }
            check(models.isNotEmpty()) { text(R.string.cloud_voice_no_models) }
            notice = text(R.string.cloud_voice_model_count, models.size)
        }
    }) { Text(text(R.string.cloud_voice_load_models)) }
    val voiceOptions = (voices.map { it.id to it.label } + CloudVoiceCatalog.voices(config).map { it to it } +
        listOf(config.voice to config.voice)).filter { it.first.isNotBlank() }.distinctBy { it.first }
    if (voiceOptions.isNotEmpty()) VoiceMenu(text(R.string.cloud_voice_voice), config.voice, voiceOptions) { stopPreview(); config = config.copy(voice = it, providerDefaultVoice = false) }
    if (config.provider == CloudVoiceProvider.ELEVENLABS) OutlinedButton(enabled = !busy, onClick = {
        val snapshot = config; val page = nextPage
        launchWork {
            val c = CloudVoiceClient(); client = c
            val result = withContext(Dispatchers.IO) {
                val secret = key.takeIf { it.isNotBlank() } ?: CloudVoiceStore.key(context, snapshot)
                CloudVoiceProtocol.voices(c.catalog(CloudVoiceProtocol.voicesRequest(page), secret))
            }
            voices = ((if (page == null) emptyList() else voices) + result.voices).distinctBy { it.id }.take(1000)
            nextPage = result.next.takeIf { voices.size < 1000 }
            notice = text(R.string.cloud_voice_voice_count, voices.size)
        }
    }) { Text(text(if (nextPage == null) R.string.cloud_voice_load_voices else R.string.cloud_voice_more_voices)) }
    if (config.supportsSpeed()) {
        Text(text(R.string.ui_speed_1_s_7d1e1, "%.2f".format(config.speed)))
        Slider(config.speed, { config = config.copy(speed = it) }, enabled = !busy,
            valueRange = if (config.provider == CloudVoiceProvider.ELEVENLABS) .7f..1.2f else .5f..2f)
    }
    TextButton(onClick = { advanced = !advanced }) { Text(text(if (advanced) R.string.cloud_voice_less else R.string.cloud_voice_advanced)) }
    if (config.provider == CloudVoiceProvider.OPENROUTER) {
        Row { Checkbox(config.providerDefaultVoice, enabled = !busy, onCheckedChange = {
            config = config.copy(providerDefaultVoice = it, voice = if (it) "" else CloudVoiceCatalog.voices(config).firstOrNull().orEmpty())
        }); Text(text(R.string.cloud_voice_provider_default), modifier = Modifier.padding(top = 12.dp)) }
    }
    if (advanced || voiceOptions.isEmpty() && !config.providerDefaultVoice) {
        OutlinedTextField(config.model, { config = config.copy(model = it) }, enabled = !busy,
            label = { Text(text(R.string.cloud_voice_model_id)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(config.voice, { stopPreview(); config = config.copy(voice = it, providerDefaultVoice = false) }, enabled = !busy,
            label = { Text(text(R.string.cloud_voice_voice_id)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text(text(R.string.cloud_voice_gender_info), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(config.femaleVoice, { config = config.copy(femaleVoice = it) }, enabled = !busy,
            label = { Text(text(R.string.cloud_voice_female)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(config.maleVoice, { config = config.copy(maleVoice = it) }, enabled = !busy,
            label = { Text(text(R.string.cloud_voice_male)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
    if (config.supportsInstructions()) OutlinedTextField(config.instructions, { config = config.copy(instructions = it.take(1000)) },
        enabled = !busy, label = { Text(text(R.string.cloud_voice_style)) }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy, onClick = {
        val snapshot = config; val replacement = key.takeIf { it.isNotBlank() }
        launchWork {
            withContext(Dispatchers.IO) { CloudVoiceStore.save(context, snapshot, replacement, requireVoice = false) }
            saved = snapshot; key = ""; hasKey = CloudVoiceStore.hasKey(context, snapshot); notice = text(R.string.cloud_voice_saved)
        }
    }) { Text(text(R.string.cloud_voice_save)) }
    if (hasKey) TextButton(enabled = !busy, onClick = {
        stopPreview()
        val snapshot = config
        launchWork { withContext(Dispatchers.IO) { CloudVoiceStore.removeKey(context, snapshot) }; hasKey = false }
    }) { Text(text(R.string.cloud_voice_remove_key)) }
    OutlinedButton(enabled = !busy && !dirty && hasKey, onClick = {
        launchWork {
            config.validated()
            CaptionConfigStore.update(context) { it.copy(speakerChoice = CaptionSpeakerChoice.CLOUD) }
            notice = text(R.string.cloud_voice_overlay_ready)
        }
    }) { Text(text(R.string.cloud_voice_use_overlay)) }
    HorizontalDivider()
    Text(text(R.string.cloud_voice_preview_notice), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(sample, { sample = it.take(5000) }, label = { Text(text(R.string.ui_text_to_preview_32ed3)) }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && !previewing && !dirty && hasKey && sample.isNotBlank(), onClick = {
            error = null; speaker?.release(); val generation = ++previewSequence
            speaker = CloudTtsSpeaker({ CloudVoiceStore.config(context) }, { CloudVoiceStore.key(context, it) },
                { if (generation == previewSequence) error = it }, context.cacheDir,
                context.applicationContext.getSystemService(android.media.AudioManager::class.java),
                { if (generation == previewSequence) previewing = it })
            speaker?.speak(sample, "und")
        }) { Text(text(R.string.ui_preview_selected_voice_abd2c)) }
        OutlinedButton(onClick = { stop(); notice = null }) { Text(text(R.string.ui_stop_9e253)) }
    }
    if (previewing) Text(text(R.string.ui_preparing_or_playing_voice_4e5bc), color = MaterialTheme.colorScheme.primary)
    if (dirty) Text(text(R.string.cloud_voice_save_first), style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
