package com.sal7one.transiber.voice

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.SpeakerGender
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.settings.VoiceMenu
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** Text → voice is independent of capture, ASR, translation and caption overlay settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TtsScreen(
    onBack: () -> Unit,
    onVoiceSettings: () -> Unit,
    onCloudSettings: () -> Unit,
    onModels: () -> Unit,
) {
    val text = rememberUiText()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("standalone-tts-v1", 0) }
    val initial = remember { VoiceSettings.choice(context) }
    var input by rememberSaveable { mutableStateOf(prefs.getString("draft", "").orEmpty().take(TtsPlaybackRequest.MAX_TEXT_CHARACTERS)) }
    var route by rememberSaveable { mutableStateOf(prefs.getString("route", initial.backend).orEmpty().let {
        it.takeIf { v -> v in setOf("system", "supertonic") || ByokPolicy.FEATURE_BYOK && v in setOf("remote", "cloud") } ?: "system"
    }) }
    var language by rememberSaveable { mutableStateOf(prefs.getString("language", "en").orEmpty()) }
    var voice by rememberSaveable { mutableStateOf(prefs.getString("voice", initial.voice).orEmpty().takeIf { it in VoiceCatalog.voices } ?: "F1") }
    var rate by rememberSaveable { mutableFloatStateOf(prefs.getFloat("rate", initial.rate).let { if (it.isFinite()) it.coerceIn(.5f, 2f) else 1f }) }
    var steps by rememberSaveable { mutableIntStateOf(prefs.getInt("steps", initial.steps).coerceIn(2, 12)) }
    var gender by rememberSaveable { mutableStateOf(prefs.getString("gender", VoiceGender.ANY.name).orEmpty()
        .takeIf { stored -> VoiceGender.entries.any { it.name == stored } } ?: VoiceGender.ANY.name) }
    var volume by rememberSaveable { mutableFloatStateOf(prefs.getFloat("volume", 1f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }) }
    var engine by rememberSaveable { mutableStateOf(prefs.getString("android-engine", "").orEmpty()) }
    var androidVoice by rememberSaveable { mutableStateOf(prefs.getString("android-voice", "").orEmpty()) }
    var serverVoice by rememberSaveable { mutableStateOf(prefs.getString("server-voice", "").orEmpty()) }
    var speaking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var extraModels by rememberSaveable { mutableStateOf(false) }
    var engineChoices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var voices by remember { mutableStateOf(emptyList<android.speech.tts.Voice>()) }
    var loadingVoices by remember { mutableStateOf(false) }
    var voiceDiscoveryComplete by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var playbackGeneration by remember { mutableLongStateOf(0L) }
    val player = remember { VoicePlayer(context) { active, failure -> speaking = active; if (failure != null) error = failure } }
    var cloudSpeaker by remember { mutableStateOf<CloudTtsSpeaker?>(null) }
    val models = remember { VoiceModels(File(context.filesDir, "voice-models")) }
    fun stop() { playbackGeneration++; cloudSpeaker?.stop(); player.stop(); speaking = false }
    fun saveDraft() { prefs.edit().putString("draft", input).putString("route", route).putString("language", language)
        .putFloat("volume", volume).putString("android-engine", engine).putString("android-voice", androidVoice)
        .putString("server-voice", serverVoice).putString("voice", voice).putFloat("rate", rate)
        .putInt("steps", steps).putString("gender", gender).apply() }
    BackHandler { stop(); saveDraft(); onBack() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stop(); saveDraft() }
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cloudSpeaker?.release(); player.close(); saveDraft() }
    }
    // Engine and voice discovery cannot send text or make a paid speech request.
    DisposableEffect(engine, refresh, route) {
        if (route != "system") {
            loadingVoices = false
            onDispose { }
        } else {
        var disposed = false
        var completed = false
        var probe: TextToSpeech? = null
        val handler = Handler(Looper.getMainLooper())
        loadingVoices = true; voiceDiscoveryComplete = false; voices = emptyList()
        val timeout = Runnable { if (!disposed && !completed) {
            completed = true; loadingVoices = false; error = text(R.string.tts_engine_timeout); probe?.shutdown(); probe = null
        } }
        handler.postDelayed(timeout, 15_000)
        probe = TextToSpeech(context, { status -> handler.post {
            if (!disposed && !completed) {
                completed = true
                handler.removeCallbacks(timeout); loadingVoices = false
                if (status == TextToSpeech.SUCCESS) {
                    engineChoices = probe?.engines.orEmpty().sortedBy { it.label }.map { it.name to it.label }
                    if (engine.isNotBlank() && engineChoices.none { it.first == engine }) {
                        error = text(R.string.tts_engine_missing); voices = emptyList()
                    } else {
                        voices = probe?.voices.orEmpty().filterNot { it.isNetworkConnectionRequired }.sortedBy { it.name }
                        voiceDiscoveryComplete = true
                    }
                } else error = "Android TTS initialization returned $status"
                probe?.shutdown(); probe = null
            }
        } }, engine.ifBlank { null })
        onDispose { disposed = true; handler.removeCallbacks(timeout); probe?.shutdown() }
        }
    }
    val languages = when (route) {
        "supertonic" -> VoiceCatalog.languages
        "system" -> voices.map { it.locale.language }.toSet()
        "remote" -> runCatching { VoiceSettings.remote(context).capabilities.languages.toSet() }.getOrDefault(emptySet())
        else -> LanguageCatalog.whisperCodes
    }
    val preferredLanguage = Locale.forLanguageTag(language).language
    val localVoices = voices.filter { it.locale.language == preferredLanguage }
    val missingAndroidVoice = route == "system" && OfflineVoicePolicy.savedVoiceMissing(
        voices.map { OfflineVoicePolicy.Candidate(it.name, it.locale, it.isNetworkConnectionRequired, it.quality) },
        Locale.forLanguageTag(language), androidVoice.ifBlank { null }, voiceDiscoveryComplete && !loadingVoices)
    LaunchedEffect(route, languages) {
        if (languages.isNotEmpty() && language !in languages && preferredLanguage !in languages) {
            language = listOf("ar", "en", "zh").firstOrNull { it in languages } ?: languages.sorted().first()
        }
    }
    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(input) }
                    ?: error("Cannot write the selected document") }
                notice = text(R.string.tts_text_saved)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }
    fun speak() {
        stop(); error = null; notice = null; saveDraft()
        val requestedGender = VoiceGender.valueOf(gender)
        if (route == "cloud") {
            if (!ByokPolicy.FEATURE_BYOK) { error = text(R.string.cloud_voice_offline); return }
            val generation = ++playbackGeneration
            cloudSpeaker?.release()
            cloudSpeaker = CloudTtsSpeaker({ CloudVoiceStore.config(context) }, { CloudVoiceStore.key(context, it) },
                { if (generation == playbackGeneration) error = it }, context.cacheDir,
                context.applicationContext.getSystemService(AudioManager::class.java),
                { if (generation == playbackGeneration) speaking = it })
            cloudSpeaker?.speak(input, language, SpeakerGender.valueOf(gender), (volume * 100).toInt())
        } else {
            player.speak(TtsPlaybackRequest(input, language, VoiceChoice(route, voice, rate, steps), requestedGender,
                volume, engine.ifBlank { null }.takeIf { route == "system" }, androidVoice.ifBlank { null }.takeIf { route == "system" },
                serverVoice.ifBlank { null }.takeIf { route == "remote" }))
        }
    }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text(R.string.tts_feature_subtitle), style = MaterialTheme.typography.bodyMedium)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(input, { input = it.take(TtsPlaybackRequest.MAX_TEXT_CHARACTERS) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(text(R.string.tts_input)) }, minLines = 4, maxLines = 9,
                        supportingText = { Text("${input.length} / ${TtsPlaybackRequest.MAX_TEXT_CHARACTERS}") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = ::speak, enabled = input.isNotBlank() && !speaking && !(route == "system" && loadingVoices),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(text(R.string.tts_speak))
                        }
                        OutlinedButton(onClick = ::stop, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text(text(R.string.tts_stop))
                        }
                    }
                    if (speaking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(text(R.string.tts_working), color = MaterialTheme.colorScheme.primary) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val routes = listOf("system" to text(R.string.tts_android), "supertonic" to "Supertonic 3") +
                    if (ByokPolicy.FEATURE_BYOK) listOf("remote" to text(R.string.tts_server), "cloud" to text(R.string.tts_cloud)) else emptyList()
                routes.forEach { (id, label) -> FilterChip(route == id, { stop(); route = id; error = null }, label = { Text(label) }) }
            }
            when (route) {
                "system" -> {
                    VoiceMenu(text(R.string.tts_engine), engine, listOf("" to text(R.string.tts_android_default)) + engineChoices) {
                        stop(); engine = it; androidVoice = ""; error = null
                    }
                    if (loadingVoices) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (missingAndroidVoice) Text(text(R.string.tts_voice_missing, androidVoice), color = MaterialTheme.colorScheme.error)
                    if (!loadingVoices && languages.isEmpty()) Text(text(R.string.tts_no_offline_voice), color = MaterialTheme.colorScheme.error)
                }
                "supertonic" -> {
                    if (!models.ready(voice)) OutlinedButton(onClick = { stop(); onVoiceSettings() }) { Text(text(R.string.tts_install_supertonic)) }
                    VoiceMenu(text(R.string.tts_voice), voice, VoiceCatalog.voices.map { it to it }) { stop(); voice = it }
                }
                "remote" -> {
                    val ready = runCatching { VoiceSettings.remote(context) }
                    if (ready.isFailure) Text(ready.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    ready.getOrNull()?.let { connection ->
                        Text(connection.capabilities.label, style = MaterialTheme.typography.titleMedium)
                        VoiceMenu(text(R.string.tts_voice), serverVoice,
                            listOf("" to text(R.string.tts_automatic)) + connection.capabilities.voices.map { it to it }) { stop(); serverVoice = it }
                    }
                    TextButton(onClick = { stop(); onVoiceSettings() }) { Text(text(R.string.tts_server_settings)) }
                }
                "cloud" -> {
                    val config = runCatching { CloudVoiceStore.config(context) }
                    config.getOrNull()?.let { Text("${it.provider.label} · ${it.model} · ${it.voice.ifBlank { text(R.string.tts_provider_voice) }}") }
                    config.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    Text(text(R.string.tts_cloud_notice), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { stop(); onCloudSettings() }) { Text(text(R.string.tts_cloud_settings)) }
                }
            }
            VoiceMenu(text(R.string.tts_language), language, LanguageCatalog.choices(languages).map { it.code to it.label }) { stop(); language = it; androidVoice = "" }
            if (route == "system" && localVoices.isNotEmpty()) VoiceMenu(text(R.string.tts_voice), androidVoice,
                listOf("" to text(R.string.tts_automatic)) + localVoices.map { it.name to "${it.locale.getDisplayName()} · ${it.name}" }) { stop(); androidVoice = it }
            Text(text(R.string.tts_volume, (volume * 100).toInt()))
            Slider(volume, { volume = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = text(R.string.tts_volume_description) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(VoiceGender.ANY to R.string.tts_any_voice, VoiceGender.FEMALE to R.string.tts_female, VoiceGender.MALE to R.string.tts_male).forEach { (value, label) ->
                    FilterChip(gender == value.name, { stop(); gender = value.name }, label = { Text(text(label)) })
                }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(text(if (expanded) R.string.tts_less else R.string.tts_more)) }
            if (expanded) {
                Text(text(R.string.tts_gender_note), style = MaterialTheme.typography.bodySmall)
                if (route != "cloud") {
                    Text(text(R.string.tts_rate, "%.2f".format(rate)))
                    Slider(rate, { stop(); rate = it }, valueRange = .5f..2f, modifier = Modifier.semantics { contentDescription = text(R.string.tts_rate_description) })
                }
                if (route == "supertonic") {
                    Text(text(R.string.tts_steps, steps))
                    Slider(steps.toFloat(), { stop(); steps = it.toInt() }, valueRange = 2f..12f, steps = 9,
                        modifier = Modifier.semantics { contentDescription = text(R.string.tts_steps_description) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = input.isNotBlank(), onClick = { exportText.launch("hearth-voice-text.txt") }) { Text(text(R.string.tts_export_text)) }
                    TextButton(onClick = { stop(); input = ""; prefs.edit().remove("draft").apply() }) { Text(text(R.string.tts_clear)) }
                }
                TextButton(onClick = { extraModels = !extraModels }) { Text(text(R.string.tts_more_models)) }
                if (extraModels) {
                    Text(text(R.string.tts_external_models), style = MaterialTheme.typography.bodySmall)
                    AndroidVoiceFamilies.families.forEach { family ->
                        if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(family.source))) }.onFailure { error = it.message }
                        }) { Text(family.name) } else Text(family.name)
                    }
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { error = it.message } }) {
                        Text(text(R.string.tts_android_settings))
                    }
                }
                TextButton(onClick = { stop(); onModels() }) { Text(text(R.string.tts_models)) }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
}
