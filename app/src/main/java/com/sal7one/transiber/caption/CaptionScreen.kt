package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

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
import com.sal7one.transiber.settings.SettingsSpeechCloudUi
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.ui.components.CapabilityStatus
import com.sal7one.transiber.ui.components.CapabilityTag
import com.sal7one.transiber.ui.components.HearthCard
import com.sal7one.transiber.ui.theme.AppDesign
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pre-flight page for the live caption overlay: configure the session, see
 * exactly what is ready and what is missing, then launch. In-session tuning
 * lives inside the overlay itself.
 */
@Composable
fun CaptionScreen(
    onBrowseModels: () -> Unit,
) {
    val uiText = rememberUiText()

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
    var cloudMode by remember { mutableStateOf(CloudConfigStore.sttMode(context)) }
    var cloudKeyStored by remember {
        mutableStateOf(ByokPolicy.cloudEngineAvailable() && ApiKeyStore.hasOpenAiKey(context))
    }
    val chosenEngineReady = when (config.effectiveEngine) {
        CaptionEngineChoice.WHISPER -> whisperModels > 0
        CaptionEngineChoice.VOSK -> voskModels > 0
        CaptionEngineChoice.MOONSHINE, CaptionEngineChoice.QWEN, CaptionEngineChoice.OMNILINGUAL, CaptionEngineChoice.NEMOTRON -> localSpeechModels.any {
            it.profile.backend == config.effectiveEngine.speechBackend && (config.modelId.isBlank() || it.id == config.modelId)
        }
        // NETWORK PATH (BYOK, play distribution only — see byok/ByokPolicy):
        // no local model needed, just the distribution + the user's key.
        CaptionEngineChoice.CLOUD ->
            ByokPolicy.cloudEngineAvailable() && (cloudKeyStored || !com.sal7one.transiber.byok.cloudSpeechKeyRequired(cloudMode,com.sal7one.transiber.byok.CloudConfigStore.provider(context)))
    }
    // Cheap, honest pre-flight check: the translation runtime is compiled in
    // AND a TRANSLATE model folder is registered. The authoritative check
    // (native engine load) happens when the session starts; a load failure
    // surfaces as an overlay notice from the caption controller.
    val translationModelImported = registered.any {
        it.engineType == ModelEngineType.TRANSLATE && it.isValid &&
            it.digest?.hex == com.sal7one.transiber.translation.MarianPackage.find(
                com.sal7one.transiber.translation.TranslationOptions.MARIAN_EN_AR)?.treeSha256
    }
    var localTranslationInstalled by remember { mutableStateOf(false) }
    LaunchedEffect(config.localTranslationModelId) {
        localTranslationInstalled = withContext(Dispatchers.IO) {
            com.sal7one.transiber.translation.TranslationOptions.installed(context, config.localTranslationModelId)
        }
    }
    val route = captionTranslationRoute(config, cloudMode)
    val liveCloudTranslation = route == CaptionTranslationRoute.LIVE_TARGET
    val translationReady = when (route) {
        CaptionTranslationRoute.TEXT_TRANSLATOR -> if (config.textTranslationProviderId !in setOf("", "local")) com.sal7one.transiber.translation.captionCloudTranslatorReady(config) else localTranslationInstalled || (config.localTranslationModelId == com.sal7one.transiber.translation.TranslationOptions.ML_KIT && com.sal7one.transiber.translation.PlatformTranslation.available)
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
                    Text(uiText(UiR.string.ui_live_captions_overlay_92a8d), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (config.effectiveEngine == CaptionEngineChoice.CLOUD) {
                            uiText(UiR.string.ui_floating_captions_over_any_app_s_audio_with_the_cloud_b70cc) +
                                uiText(UiR.string.ui_engine_utterance_audio_is_uploaded_to_your_provider_fb528) +
                                uiText(UiR.string.ui_using_your_key_nothing_else_leaves_this_phone_7d264)
                        } else {
                            uiText(UiR.string.ui_floating_captions_or_translation_over_any_app_s_audio_1e168) +
                                uiText(UiR.string.ui_everything_runs_on_this_phone_nothing_is_uploaded_c080c)
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
                SectionTitle(uiText(UiR.string.ui_watch_streams_with_live_subtitles_bcd72))
                Text(uiText(UiR.string.ui_uses_your_openai_key_and_streams_device_audio_for_live_processing_d7a42),
                    style = MaterialTheme.typography.bodySmall)
                ChipFlow {
                    listOf(uiText(UiR.string.ui_live_arabic_b472a) to TranslationTarget.ARABIC, uiText(UiR.string.ui_live_english_32c69) to TranslationTarget.ENGLISH).forEach { (label, target) ->
                        OutlinedButton(onClick = {
                            CloudConfigStore.setSttMode(context, CloudConfigStore.SttMode.STREAMING_OPENAI)
                            cloudMode = CloudConfigStore.SttMode.STREAMING_OPENAI
                            update { it.selectCaptionEngine(CaptionEngineChoice.CLOUD, cloudMode).copy(mode = CaptionMode.TRANSLATE,
                                target = target, source = CaptionSource.PLAYBACK_CAPTURE, streamLanguage = "auto", textTranslationProviderId = "",
                                tapThrough = false, xOffsetPx = 0, yOffsetPx = 0, historyLines = maxOf(it.historyLines, CaptionReading.DEFAULT_PREVIOUS_LINES)) }
                        }) { Text(label) }
                    }
                    OutlinedButton(onClick = {
                        CloudConfigStore.setSttMode(context, CloudConfigStore.SttMode.STREAMING_OPENAI)
                            cloudMode = CloudConfigStore.SttMode.STREAMING_OPENAI
                        update { it.selectCaptionEngine(CaptionEngineChoice.CLOUD, CloudConfigStore.SttMode.STREAMING_OPENAI).copy(mode = CaptionMode.CAPTIONS,
                            source = CaptionSource.PLAYBACK_CAPTURE, tapThrough = false, historyLines = maxOf(it.historyLines, CaptionReading.DEFAULT_PREVIOUS_LINES)) }
                    }) { Text(uiText(UiR.string.ui_live_cc_a34a0)) }
                }
                Text(uiText(UiR.string.ui_the_notification_controls_pause_resume_bubble_recovery_and_stop_o_9d432),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ------------------------------------------------------------- mode
        HearthCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle(uiText(UiR.string.ui_what_should_the_overlay_do_ec3a9))
            ChipFlow {
                CaptionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.mode == mode,
                        onClick = { update { it.withCaptionMode(mode) } },
                        label = { Text(uiText.label(mode)) },
                    )
                }
            }
            CaptionLanguageFields(config, update)
            if (config.mode == CaptionMode.TRANSLATE) {
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
                            uiText(UiR.string.ui_1_s_live_translation_2_s_no_on_device_translation_model_needed_d2ee4, if (cloudMode == CloudConfigStore.SttMode.STREAMING_SONIOX) "Soniox" else "OpenAI", config.target.label)
                        } else if (translationReady) {
                            uiText(UiR.string.ui_translation_route_available_model_api_errors_will_appear_in_the_b_40687)
                        } else {
                            uiText(UiR.string.ui_choose_a_translator_in_the_local_translation_section_below_origin_507ac)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(AppDesign.Dimens.SpacingSm))
            Text(uiText(UiR.string.ui_caption_engine_a52ee), style = MaterialTheme.typography.labelLarge)
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
                            onClick = { update { it.selectCaptionEngine(engine, cloudMode) } },
                            label = { Text(uiText.label(engine)) },
                        )
                    }
            }
            Text(
                text = if (englishTranslate) {
                    uiText.explanation(config.effectiveEngine) + "\n" +
                        uiText(UiR.string.ui_english_translation_whisper_cloud_translate_in_one_pass_87424) +
                        uiText(UiR.string.ui_an_english_vosk_model_gives_instant_english_captions_b038f)
                } else {
                    uiText.explanation(config.effectiveEngine)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Explicit speech-model picker when several models of the running
            // engine type are imported (e.g. Arabic Vosk vs English Vosk).
            // Whisper models are multilingual; Vosk models are single-language,
            // so pick the Vosk matching the stream's language.
            if (config.effectiveEngine.speechBackend != null) {
                LocalSpeechSetup(config, update, includeTranslation = false) { localSpeechModels = it }
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
                        CaptionEngineChoice.CLOUD, CaptionEngineChoice.MOONSHINE, CaptionEngineChoice.QWEN, CaptionEngineChoice.OMNILINGUAL, CaptionEngineChoice.NEMOTRON -> ModelEngineType.WHISPER // unreachable
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
                SettingsSpeechCloudUi(onStoredChange = { stored -> cloudKeyStored = stored }, onModeChange = { cloudMode = it })
            }
            if (engineModels.isNotEmpty()) {
                Spacer(Modifier.height(AppDesign.Dimens.SpacingSm))
                Text(uiText(UiR.string.ui_speech_model_3c5f0), style = MaterialTheme.typography.labelLarge)
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
                        uiText(UiR.string.ui_vosk_models_transcribe_one_language_each_choose_the_one_that_eea34) +
                            uiText(UiR.string.ui_matches_the_stream_you_are_watching_0e03a)
                    } else {
                        uiText(UiR.string.ui_whisper_models_are_multilingual_the_choice_is_a_speed_accuracy_tr_33925)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        com.sal7one.transiber.translation.CaptionTranslatorChooser(config, update, onBrowseModels)
        if (config.effectiveEngine == CaptionEngineChoice.WHISPER || config.effectiveEngine == CaptionEngineChoice.VOSK) {
            HearthCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(uiText(UiR.string.ui_model_files_imports_233e7))
                com.sal7one.transiber.models.ModelSourcePanel(com.sal7one.transiber.models.ModelSources.speech(config.effectiveEngine))
                TextButton(onClick = onBrowseModels) { Text(uiText(UiR.string.ui_manage_installed_speech_translation_models_588a3)) }
            }
        }

        // ----------------------------------------------------------- source
        HearthCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle(uiText(UiR.string.ui_audio_source_a1ee0))
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
                                Text(uiText.label(source), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    uiText.explanation(source),
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
            SectionTitle(uiText(UiR.string.ui_before_you_start_2c445))
            RequirementRow(
                done = overlayAllowed,
                title = uiText(UiR.string.ui_display_over_other_apps_d3869),
                detail = if (overlayAllowed) {
                    uiText(UiR.string.ui_granted_the_caption_bubble_can_draw_over_any_app_a88cf)
                } else {
                    uiText(UiR.string.ui_one_time_android_setting_so_the_caption_bubble_can_float_over_the_b7ec0)
                },
                actionLabel = if (overlayAllowed) null else uiText(UiR.string.ui_open_setting_6786d),
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
                title = uiText(UiR.string.ui_a_1_s_speech_model_a1c7e, uiText.label(config.effectiveEngine)),
                detail = if (chosenEngineReady) {
                    when (config.effectiveEngine) {
                        CaptionEngineChoice.WHISPER -> uiText(UiR.string.ui_1_s_whisper_model_s_imported_9e09e, whisperModels)
                        CaptionEngineChoice.VOSK -> uiText(UiR.string.ui_1_s_vosk_model_s_imported_1d5f4, voskModels)
                        CaptionEngineChoice.MOONSHINE, CaptionEngineChoice.QWEN, CaptionEngineChoice.OMNILINGUAL, CaptionEngineChoice.NEMOTRON -> uiText(UiR.string.ui_verified_local_speech_package_imported_af455)
                        CaptionEngineChoice.CLOUD -> uiText(UiR.string.ui_cloud_captions_ready_api_key_stored_492e1)
                    }
                } else {
                    when (config.effectiveEngine) {
                        CaptionEngineChoice.MOONSHINE, CaptionEngineChoice.QWEN, CaptionEngineChoice.OMNILINGUAL, CaptionEngineChoice.NEMOTRON -> uiText(UiR.string.ui_use_import_model_zip_in_the_model_setup_above_ce1a6)
                        CaptionEngineChoice.CLOUD ->
                            uiText(UiR.string.ui_paste_your_api_key_in_the_cloud_engine_your_api_key_box_above_480e0)
                        else ->
                            uiText(UiR.string.ui_open_models_speech_to_get_files_or_import_an_installed_model_76586)
                    }
                },
                actionLabel = if (chosenEngineReady || config.effectiveEngine.speechBackend != null) null else uiText(UiR.string.ui_browse_models_3221a),
                onAction = onBrowseModels,
            )
        }

        // ------------------------------------------------------------- go
        val ready = overlayAllowed && chosenEngineReady
        Button(
            onClick = { CaptionStartActivity.start(context) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.VolumeUp, null)
            Text(uiText(UiR.string.ui_start_live_captions_ed786))
        }
        if (!ready) {
            Text(
                text = buildString {
                    if (!overlayAllowed) append(uiText(UiR.string.ui_grant_the_overlay_setting_7eef5))
                    if (!chosenEngineReady) {
                        if (config.effectiveEngine == CaptionEngineChoice.CLOUD) {
                            append(uiText(UiR.string.ui_paste_your_api_key_in_the_cloud_section_above_2d3b0))
                        } else {
                            append(uiText(UiR.string.ui_import_a_speech_model_b8766))
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
                Text(uiText(UiR.string.ui_how_android_limits_device_audio_capture_83b77), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = uiText(UiR.string.ui_apps_decide_whether_their_audio_can_be_captured_drm_video_94494) +
                        uiText(UiR.string.ui_youtube_app_netflix_is_never_capturable_tiktok_must_be_tried_per_af7d4) +
                        uiText(UiR.string.ui_when_nothing_capturable_is_playing_the_overlay_says_so_and_you_ca_d2b93) +
                        uiText(UiR.string.ui_switch_to_the_microphone_source_which_works_with_any_app_6df91) +
                        uiText(UiR.string.ui_captions_pause_automatically_after_a_few_seconds_of_silence_6effc) +
                        uiText(UiR.string.ui_english_translation_is_one_whisper_pass_other_targets_keep_origin_6597f) +
                        uiText(UiR.string.ui_captions_live_while_the_translation_stage_catches_up_4fdd3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBrowseModels) {
                    Icon(Icons.Default.ModelTraining, null, modifier = Modifier.size(16.dp))
                    Text(uiText(UiR.string.ui_browse_caption_translation_models_4dbad))
                }
            }
        }
        OutlinedButton(
            onClick = { CaptionCaptureService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(uiText(UiR.string.ui_stop_a_running_session_c84e9))
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
    val uiText = rememberUiText()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDesign.Dimens.SpacingSm),
    ) {
        Icon(
            Icons.Default.Verified,
            contentDescription = if (done) uiText(UiR.string.ui_ready_20c7c) else uiText(UiR.string.ui_not_ready_2b50f),
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
