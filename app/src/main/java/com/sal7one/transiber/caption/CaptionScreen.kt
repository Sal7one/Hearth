package com.sal7one.transiber.caption

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.common_jni.marian.MarianTranslatorEngine
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.byok.ApiKeyStore
import com.sal7one.transiber.byok.ByokKeySection
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.ui.components.CapabilityStatus
import com.sal7one.transiber.ui.components.CapabilityTag
import com.sal7one.transiber.ui.components.HearthCard
import com.sal7one.transiber.ui.theme.AppDesign
import kotlinx.coroutines.launch

/**
 * Pre-flight page for the live caption overlay: configure the session, see
 * exactly what is ready and what is missing, then launch. In-session tuning
 * lives inside the overlay itself.
 */
@Composable
fun CaptionScreen(
    onBrowseModels: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { ModelRegistry.getInstance(context) }
    val registered by registry.registeredModels.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(CaptionOverlayConfig()) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Load the persisted configuration once, then keep local edits flowing
    // back to the store so the service starts with what is shown here.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        CaptionConfigStore.config(context).collect { config = it }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayAllowed = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
        val next = transform(config).withUiClamp()
        config = next
        scope.launch { CaptionConfigStore.update(context) { next } }
    }

    var localSpeechModels by remember { mutableStateOf<List<LocalSpeechModel>>(emptyList()) }
    val whisperModels = registered.count {
        it.engineType == ModelEngineType.WHISPER && it.isValid
    }
    val voskModels = registered.count {
        it.engineType == ModelEngineType.VOSK && it.isValid
    }
    // Key state lives here (not inside the section) so engine readiness
    // never re-decrypts the key on recomposition — it refreshes only on
    // save/remove. Play build only; FOSS has no key storage UI at all.
    var cloudKeyStored by remember {
        mutableStateOf(ByokPolicy.cloudEngineAvailable() && ApiKeyStore.hasOpenAiKey(context))
    }
    val chosenEngineReady = when (config.effectiveEngine) {
        CaptionEngineChoice.WHISPER -> whisperModels > 0
        CaptionEngineChoice.VOSK -> voskModels > 0
        CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON -> localSpeechModels.any {
            it.profile.backend == config.effectiveEngine.speechBackend && (config.modelId.isBlank() || it.id == config.modelId)
        }
        // NETWORK PATH (BYOK, play distribution only — see byok/ByokPolicy):
        // no local model needed, just the distribution + the user's key.
        CaptionEngineChoice.CLOUD ->
            ByokPolicy.cloudEngineAvailable() && cloudKeyStored
    }
    // Cheap, honest pre-flight check: the translation runtime is compiled in
    // AND a TRANSLATE model folder is registered. The authoritative check
    // (native engine load) happens when the session starts; a load failure
    // surfaces as an overlay notice from the caption controller.
    val translationModelImported = registered.any {
        it.engineType == ModelEngineType.TRANSLATE && it.isValid
    }
    val route = captionTranslationRoute(config, CloudConfigStore.sttMode(context))
    val liveCloudTranslation = route == CaptionTranslationRoute.LIVE_TARGET
    val translationReady = when (route) {
        CaptionTranslationRoute.UNSUPPORTED -> false
        CaptionTranslationRoute.ENGLISH_PIVOT, CaptionTranslationRoute.ENGLISH_TEXT -> translationModelImported && MarianTranslatorEngine.isRuntimeAvailable
        else -> true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDesign.Dimens.SpacingMd),
        verticalArrangement = Arrangement.spacedBy(AppDesign.Dimens.SpacingMd),
    ) {
        // ---------------------------------------------------------- summary
        HearthCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppDesign.Dimens.SpacingMd),
            ) {
                Icon(
                    Icons.Default.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live captions overlay", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (config.effectiveEngine == CaptionEngineChoice.CLOUD) {
                            "Floating captions over any app's audio. With the Cloud " +
                                "engine, utterance audio is uploaded to YOUR provider " +
                                "using your key — nothing else leaves this phone."
                        } else {
                            "Floating captions or translation over any app's audio. " +
                                "Everything runs on this phone — nothing is uploaded."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppDesign.Dimens.SpacingMd),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CapabilityTag(
                    status = if (overlayAllowed) CapabilityStatus.READY else CapabilityStatus.SETUP_NEEDED,
                    detail = "overlay",
                )
                CapabilityTag(
                    status = if (chosenEngineReady) CapabilityStatus.READY else CapabilityStatus.SETUP_NEEDED,
                    detail = "speech model",
                )
                CapabilityTag(
                    status = if (translationReady) CapabilityStatus.READY else CapabilityStatus.PARTLY_READY,
                    detail = "translation",
                )
            }
        }

        if (ByokPolicy.cloudEngineAvailable()) {
            HearthCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Watch streams with live subtitles")
                Text("Uses your OpenAI key and streams device audio for live processing. Arabic and English use live translation; CC keeps the spoken language. No local model is needed.",
                    style = MaterialTheme.typography.bodySmall)
                ChipFlow {
                    listOf("Live Arabic" to TranslationTarget.ARABIC, "Live English" to TranslationTarget.ENGLISH).forEach { (label, target) ->
                        OutlinedButton(onClick = {
                            CloudConfigStore.setSttMode(context, CloudConfigStore.SttMode.STREAMING_OPENAI)
                            update { it.copy(engine = CaptionEngineChoice.CLOUD, mode = CaptionMode.TRANSLATE,
                                target = target, source = CaptionSource.PLAYBACK_CAPTURE, streamLanguage = "auto",
                                tapThrough = false, xOffsetPx = 0, yOffsetPx = 0, historyLines = maxOf(it.historyLines, CaptionReading.DEFAULT_PREVIOUS_LINES)) }
                        }) { Text(label) }
                    }
                    OutlinedButton(onClick = {
                        CloudConfigStore.setSttMode(context, CloudConfigStore.SttMode.STREAMING_OPENAI)
                        update { it.copy(engine = CaptionEngineChoice.CLOUD, mode = CaptionMode.CAPTIONS,
                            source = CaptionSource.PLAYBACK_CAPTURE, tapThrough = false, historyLines = maxOf(it.historyLines, CaptionReading.DEFAULT_PREVIOUS_LINES)) }
                    }) { Text("Live CC") }
                }
                Text("The notification controls Pause/Resume, bubble recovery and Stop. OpenAI live translation detects the source language automatically.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ------------------------------------------------------------- mode
        HearthCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("What should the overlay do?")
            ChipFlow {
                CaptionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.mode == mode,
                        onClick = { update { it.copy(mode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (config.mode == CaptionMode.TRANSLATE) {
                Spacer(Modifier.height(AppDesign.Dimens.SpacingSm))
                Text(
                    "Translate into",
                    style = MaterialTheme.typography.labelLarge,
                )
                ChipFlow {
                    TranslationTarget.entries.forEach { target ->
                        FilterChip(
                            selected = config.target == target,
                            onClick = { update { it.copy(target = target) } },
                            label = { Text(target.label) },
                        )
                    }
                }

                // Pin the stream language: Whisper auto-detection is unreliable
                // on short streaming windows (whisper.cpp #445), which made
                // translation look broken for non-English streams.
                Text(
                    "Stream language",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ChipFlow {
                    STREAM_LANGUAGES.forEach { (label, code) ->
                        FilterChip(
                            selected = config.streamLanguage == code,
                            onClick = { update { it.copy(streamLanguage = code) } },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    text = if (config.streamLanguage == "auto") {
                        "Auto-detect (faster to set up, less reliable on short clips)."
                    } else {
                        "Pinned: the engine skips detection — more accurate and faster."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Icon(
                        if (translationReady) Icons.Default.Verified else Icons.Default.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (liveCloudTranslation) {
                            "OpenAI live translation → ${config.target.label}; no on-device translation model needed."
                        } else if (translationReady) {
                            "Translation route available; model/API errors will appear in the bubble."
                        } else {
                            "This route needs a compatible translation model or provider. Choose Live Arabic / Live English for OpenAI translation without a local model."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(AppDesign.Dimens.SpacingSm))
            Text("Caption engine", style = MaterialTheme.typography.labelLarge)
            // Every engine works in every mode: Whisper/Cloud translate into
            // English in one pass, and a Vosk English model IS the English
            // output (identity fast path) — so no engine is ever locked.
            // The chips reflect the engine that actually runs
            // (effectiveEngine), not the stored value.
            val englishTranslate = config.mode == CaptionMode.TRANSLATE &&
                TranslationLayer.whisperHandlesTarget(config.target)
            ChipFlow {
                CaptionEngineChoice.entries
                    // The cloud engine exists only in the play (BYOK)
                    // distribution; the FOSS build never shows it.
                    .filter { it != CaptionEngineChoice.CLOUD || ByokPolicy.cloudEngineAvailable() }
                    .forEach { engine ->
                        FilterChip(
                            selected = config.effectiveEngine == engine,
                            onClick = { update { it.copy(engine = engine, modelId = "", streamLanguage = if (engine.speechBackend != null) "auto" else it.streamLanguage) } },
                            label = { Text(engine.label) },
                        )
                    }
            }
            Text(
                text = if (englishTranslate) {
                    config.effectiveEngine.explanation + "\n" +
                        "English translation: Whisper/Cloud translate in one pass; " +
                        "an English Vosk model gives instant English captions."
                } else {
                    config.effectiveEngine.explanation
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Explicit speech-model picker when several models of the running
            // engine type are imported (e.g. Arabic Vosk vs English Vosk).
            // Whisper models are multilingual; Vosk models are single-language,
            // so pick the Vosk matching the stream's language.
            if (config.effectiveEngine.speechBackend != null) {
                LocalSpeechSetup(config, update) { localSpeechModels = it }
            }
            val engineModels = if (config.effectiveEngine == CaptionEngineChoice.CLOUD || config.effectiveEngine.speechBackend != null) {
                // Cloud engine: no local speech models involved — never show
                // the local model picker for it (it would write a modelId
                // the cloud engine ignores).
                emptyList()
            } else {
                registered.filter { model ->
                    model.isValid && model.engineType == when (config.effectiveEngine) {
                        CaptionEngineChoice.WHISPER -> ModelEngineType.WHISPER
                        CaptionEngineChoice.VOSK -> ModelEngineType.VOSK
                        CaptionEngineChoice.CLOUD, CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON -> ModelEngineType.WHISPER // unreachable
                    }
                }
            }
            if (config.effectiveEngine == CaptionEngineChoice.CLOUD &&
                ByokPolicy.cloudEngineAvailable()
            ) {
                // The API key entry lives HERE — right where the Cloud
                // engine is picked. (The overlay's gear panel and app
                // Settings can change it mid-session too.)
                // NETWORK CODE — play distribution only.
                Spacer(Modifier.height(AppDesign.Dimens.SpacingSm))
                ByokKeySection(onStoredChange = { stored -> cloudKeyStored = stored })
            }
            if (engineModels.size > 1) {
                Spacer(Modifier.height(AppDesign.Dimens.SpacingSm))
                Text("Speech model", style = MaterialTheme.typography.labelLarge)
                ChipFlow {
                    engineModels.forEach { model ->
                        FilterChip(
                            selected = config.modelId == model.id ||
                                (config.modelId.isBlank() && model == engineModels.first()),
                            onClick = { update { it.copy(modelId = model.id) } },
                            label = { Text(model.name, maxLines = 1) },
                        )
                    }
                }
                Text(
                    text = if (config.effectiveEngine == CaptionEngineChoice.VOSK) {
                        "Vosk models transcribe ONE language each — choose the one that " +
                            "matches the stream you are watching."
                    } else {
                        "Whisper models are multilingual; the choice is a speed/accuracy trade."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ----------------------------------------------------------- source
        HearthCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Audio source")
            config.source.let { current ->
                CaptionSource.entries.forEach { source ->
                    val selected = current == source
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppDesign.Dimens.RadiusMd),
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        onClick = { update { it.copy(source = source) } },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    source.explanation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------- requirements
        HearthCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Before you start")
            RequirementRow(
                done = overlayAllowed,
                title = "Display over other apps",
                detail = if (overlayAllowed) {
                    "Granted — the caption bubble can draw over any app."
                } else {
                    "One-time Android setting so the caption bubble can float over the app you watch."
                },
                actionLabel = if (overlayAllowed) null else "Open setting",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )
            RequirementRow(
                done = chosenEngineReady,
                title = "A ${config.effectiveEngine.label} speech model",
                detail = if (chosenEngineReady) {
                    when (config.effectiveEngine) {
                        CaptionEngineChoice.WHISPER -> "$whisperModels Whisper model(s) imported"
                        CaptionEngineChoice.VOSK -> "$voskModels Vosk model(s) imported"
                        CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON -> "Verified local speech package imported"
                        CaptionEngineChoice.CLOUD -> "Cloud captions ready — API key stored"
                    }
                } else {
                    when (config.effectiveEngine) {
                        CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON -> "Use Import model ZIP in the model setup above."
                        CaptionEngineChoice.CLOUD ->
                            "Paste your API key in the 'Cloud engine · your API key' box above."
                        else ->
                            "Import a speech model from the catalogue — the Live captions section lists tested options."
                    }
                },
                actionLabel = if (chosenEngineReady || config.effectiveEngine.speechBackend != null) null else "Browse models",
                onAction = onBrowseModels,
            )
        }

        // ------------------------------------------------------------- go
        val ready = overlayAllowed && chosenEngineReady &&
            (config.effectiveEngine.speechBackend == null || config.mode == CaptionMode.CAPTIONS)
        Button(
            onClick = { CaptionStartActivity.start(context) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.VolumeUp, null)
            Text("  Start live captions")
        }
        if (!ready) {
            Text(
                text = buildString {
                    if (!overlayAllowed) append("Grant the overlay setting. ")
                    if (!chosenEngineReady) {
                        if (config.effectiveEngine == CaptionEngineChoice.CLOUD) {
                            append("Paste your API key in the cloud section above. ")
                        } else {
                            append("Import a speech model. ")
                        }
                    }
                }.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------ limitations
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(AppDesign.Dimens.RadiusMd),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("How Android limits device-audio capture", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "• Apps decide whether their audio can be captured. DRM video " +
                        "(YouTube app, Netflix) is never capturable; TikTok must be tried per device.\n" +
                        "• When nothing capturable is playing, the overlay says so and you can " +
                        "switch to the microphone source, which works with any app.\n" +
                        "• Captions pause automatically after a few seconds of silence.\n" +
                        "• English translation is one Whisper pass. Other targets keep original " +
                        "captions live while the translation stage catches up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBrowseModels) {
                    Icon(Icons.Default.ModelTraining, null, modifier = Modifier.size(16.dp))
                    Text("  Browse caption & translation models")
                }
            }
        }
        OutlinedButton(
            onClick = { CaptionCaptureService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop a running session")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = AppDesign.Dimens.SpacingSm),
    )
}

@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        content()
    }
}

@Composable
private fun RequirementRow(
    done: Boolean,
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDesign.Dimens.SpacingSm),
    ) {
        Icon(
            Icons.Default.Verified,
            contentDescription = if (done) "Ready" else "Not ready",
            tint = if (done) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
