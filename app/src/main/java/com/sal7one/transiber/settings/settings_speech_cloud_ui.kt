package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The one true "Cloud engine · your API key" editor (Material styling) —
 * shared by the Live captions screen and app Settings so the key can be
 * managed wherever the user looks for it. The overlay's gear panel keeps
 * its own palette-scoped copy for mid-session changes.
 *
 * NETWORK CODE (play distribution only) — the whole composable renders
 * nothing in the FOSS build; call sites gate on ByokPolicy.FEATURE_BYOK.
 *
 * [onStoredChange] reports the new stored-state after every save/remove so
 * callers (e.g. the engine-readiness checklist) don't re-decrypt the key on
 * every recomposition.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsSpeechCloudUi(onStoredChange: (Boolean) -> Unit = {}, onModeChange: (CloudConfigStore.SttMode) -> Unit = {}) {
    val uiText = rememberUiText()

    if (!ByokPolicy.FEATURE_BYOK) return
    val context = LocalContext.current
    val selectionScope = androidx.compose.runtime.rememberCoroutineScope()
    var selectingMode by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var keyStored by remember { mutableStateOf(ApiKeyStore.hasOpenAiKey(context)) }
    var keyStoreError by remember { mutableStateOf(ApiKeyStore.lastFailure) }
    var keyRevision by remember { mutableStateOf(0) }
    var currentProvider by remember { mutableStateOf(CloudConfigStore.provider(context)) }
    var baseUrlDraft by remember { mutableStateOf(CloudConfigStore.baseUrl(context)) }
    var sttModelDraft by remember { mutableStateOf(CloudConfigStore.sttModel(context)) }
    var ttsModelDraft by remember { mutableStateOf(CloudConfigStore.ttsModel(context)) }
    var currentSttMode by remember { mutableStateOf(CloudConfigStore.sttMode(context)) }
    var deepgramStored by remember { mutableStateOf(ApiKeyStore.getDeepgramKey(context).isNotBlank()) }
    var sonioxStored by remember { mutableStateOf(ApiKeyStore.getSonioxKey(context).isNotBlank()) }
    var elevenStored by remember { mutableStateOf(ApiKeyStore.getElevenLabsKey(context).isNotBlank()) }
    var assemblyStored by remember { mutableStateOf(ApiKeyStore.getAssemblyAiKey(context).isNotBlank()) }
    LaunchedEffect(currentSttMode, keyStored, sonioxStored, elevenStored, deepgramStored, assemblyStored) {
        onStoredChange(when (currentSttMode) {
            CloudConfigStore.SttMode.STREAMING_SONIOX -> sonioxStored
            CloudConfigStore.SttMode.STREAMING_ELEVENLABS -> elevenStored
            CloudConfigStore.SttMode.STREAMING_DEEPGRAM -> deepgramStored
            CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI -> assemblyStored
            else -> keyStored
        })
    }
    val refresh: () -> Unit = {
        keyRevision++
        keyStored = ApiKeyStore.hasOpenAiKey(context)
    }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // ── STT mode: batch vs TRUE streaming (WebSocket interim results) ────
    // Streaming is the investor-facing path: interim text lands while the
    // speaker is still talking and utterances finalize in real time, instead
    // of one round-trip per audio chunk. Groq is deliberately not a
    // streaming option — verified 2026-08 their STT is REST-only; their
    // OpenAI-compatible endpoint works as a Batch provider via Custom URL.
    Spacer(Modifier.height(10.dp))
    Text(uiText(UiR.string.ui_cloud_provider_connection_49770), style = MaterialTheme.typography.labelMedium)
    // FlowRow: four mode chips would squash the last one flat in a Row.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CloudConfigStore.SttMode.entries.forEach { mode ->
            androidx.compose.material3.FilterChip(
                selected = currentSttMode == mode,
                enabled = !selectingMode,
                onClick = {
                    selectingMode = true
                    selectionScope.launch {
                        try {
                            CloudConfigStore.setSttMode(context, mode)
                            CaptionConfigStore.update(context) { it.selectCaptionEngine(CaptionEngineChoice.CLOUD, mode) }
                            currentSttMode = mode
                            onModeChange(mode)
                            keyStoreError = null
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { keyStoreError = e.message ?: e.toString() }
                        finally { selectingMode = false }
                    }
                },
                label = { Text(uiText.label(mode)) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when (currentSttMode) {
            CloudConfigStore.SttMode.STREAMING_SONIOX -> uiText(UiR.string.ui_soniox_stt_rt_v5_streaming_cc_and_optional_integrated_translation_7d848)
            CloudConfigStore.SttMode.STREAMING_ELEVENLABS -> uiText(UiR.string.ui_elevenlabs_scribe_v2_realtime_streaming_cc_with_optional_spoken_l_07f57)
            CloudConfigStore.SttMode.BATCH ->
                uiText(UiR.string.ui_batch_uploads_chunks_of_audio_and_returns_one_result_per_2ca0a) +
                    uiText(UiR.string.ui_round_trip_slowest_but_works_with_any_openai_compatible_76f1d) +
                    uiText(UiR.string.ui_endpoint_openai_openrouter_or_groq_via_a_custom_url_c2b8a)
            CloudConfigStore.SttMode.STREAMING_DEEPGRAM ->
                uiText(UiR.string.ui_deepgram_streams_audio_over_a_websocket_and_emits_interim_text_41705) +
                    uiText(UiR.string.ui_live_nova_3_is_among_the_lowest_latency_asr_on_the_market_7e53c)
            CloudConfigStore.SttMode.STREAMING_OPENAI ->
                uiText(UiR.string.ui_openai_realtime_transcription_gpt_live_transcribe_streams_72775) +
                    uiText(UiR.string.ui_deltas_over_a_websocket_uses_the_openai_key_above_2ec40)
            CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI ->
                uiText(UiR.string.ui_assemblyai_s_realtime_v3_websocket_streams_interim_final_43e88) +
                    uiText(UiR.string.ui_turns_universal_models_are_multilingual_by_default_375dd)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (currentSttMode == CloudConfigStore.SttMode.BATCH || currentSttMode == CloudConfigStore.SttMode.STREAMING_OPENAI) {
    Text(
        uiText(UiR.string.ui_cloud_engine_your_api_key_ad35b),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = keyDraft,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        onValueChange = { keyDraft = it },
        singleLine = true,
        placeholder = {
            Text(if (keyStored) uiText(UiR.string.ui_key_stored_type_to_replace_d9e13) else "sk-…")
        },
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                val stored = ApiKeyStore.setOpenAiKey(context, keyDraft)
                val storageFailure = ApiKeyStore.lastFailure
                keyDraft = ""
                if (!stored) {
                    keyStoreError = uiText(UiR.string.ui_could_not_store_the_key_on_this_device_4137a) +
                        (storageFailure ?: uiText(UiR.string.ui_storage_failed_edff3)) +
                        uiText(UiR.string.ui_nothing_was_saved_6815c)
                } else {
                    keyStoreError = if (
                        ApiKeyStore.storageMode(context) == ApiKeyStore.StorageMode.BASIC
                    ) {
                        uiText(UiR.string.ui_stored_with_basic_protection_this_device_s_keystore_c01c3) +
                            uiText(UiR.string.ui_was_unavailable_06b63) + (storageFailure ?: "unknown") + ")."
                    } else {
                        null
                    }
                }
                refresh()
            },
            enabled = keyDraft.isNotBlank(),
        ) {
            Text(if (keyStored) uiText(UiR.string.ui_replace_key_a55aa) else uiText(UiR.string.ui_save_key_f5216))
        }
        if (keyStored) {
            OutlinedButton(
                onClick = {
                    val removed = ApiKeyStore.setOpenAiKey(context, "")
                    keyStoreError = if (removed) null else ApiKeyStore.lastFailure
                    refresh()
                },
            ) {
                Text(uiText(UiR.string.ui_remove_e9639))
            }
        }
    }
    }
    CloudSourceLinks(currentSttMode)
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_SONIOX) {
        StreamingKeyField(uiText(UiR.string.ui_soniox_api_key_ea6c1), "https://console.soniox.com", sonioxStored,
            { ApiKeyStore.setSonioxKey(context, it); sonioxStored = ApiKeyStore.getSonioxKey(context).isNotBlank() },
            { ApiKeyStore.setSonioxKey(context, ""); sonioxStored = false })
    }
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_ELEVENLABS) {
        StreamingKeyField(uiText(UiR.string.ui_elevenlabs_api_key_1b0b7), "https://elevenlabs.io/app/settings/api-keys", elevenStored,
            { ApiKeyStore.setElevenLabsKey(context, it); elevenStored = ApiKeyStore.getElevenLabsKey(context).isNotBlank() },
            { ApiKeyStore.setElevenLabsKey(context, ""); elevenStored = false })
    }
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_DEEPGRAM) {
        Spacer(Modifier.height(8.dp))
        StreamingKeyField(
            label = uiText(UiR.string.ui_deepgram_api_key_05f53),
            placeholder = uiText(UiR.string.ui_paste_the_key_from_console_deepgram_com_e16ea),
            stored = deepgramStored,
            onSave = { key ->
                ApiKeyStore.setDeepgramKey(context, key)
                deepgramStored = ApiKeyStore.getDeepgramKey(context).isNotBlank()
            },
            onRemove = {
                ApiKeyStore.setDeepgramKey(context, "")
                deepgramStored = false
            },
        )
    }
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI) {
        Spacer(Modifier.height(8.dp))
        StreamingKeyField(
            label = uiText(UiR.string.ui_assemblyai_api_key_da25e),
            placeholder = uiText(UiR.string.ui_paste_the_key_from_assemblyai_com_app_account_9d6b7),
            stored = assemblyStored,
            onSave = { key ->
                ApiKeyStore.setAssemblyAiKey(context, key)
                assemblyStored = ApiKeyStore.getAssemblyAiKey(context).isNotBlank()
            },
            onRemove = {
                ApiKeyStore.setAssemblyAiKey(context, "")
                assemblyStored = false
            },
        )
    }
    var extraOptions by remember { mutableStateOf(false) }
    if (currentSttMode != CloudConfigStore.SttMode.BATCH) {
        TextButton(onClick = { extraOptions = !extraOptions }) { Text(if (extraOptions) uiText(UiR.string.ui_hide_batch_voice_options_f3f31) else uiText(UiR.string.ui_optional_batch_cloud_voice_settings_6eebe)) }
    }
    if (currentSttMode == CloudConfigStore.SttMode.BATCH || extraOptions) {
        Text(uiText(UiR.string.ui_these_endpoint_and_model_settings_affect_batch_uploads_and_option_a4bba), style = MaterialTheme.typography.bodySmall)
    // ── Provider & model ─────────────────────────────────────────────
    // Endpoint-agnostic: OpenAI keys go to OpenAI, OpenRouter keys to
    // OpenRouter, anything OpenAI-compatible to a custom URL. The model
    // ids are editable — the cloud engine reads them at session start.
    Spacer(Modifier.height(10.dp))
    Text(uiText(UiR.string.ui_provider_7ceee), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CloudConfigStore.Provider.entries.forEach { provider ->
            androidx.compose.material3.FilterChip(
                selected = currentProvider == provider,
                onClick = {
                    CloudConfigStore.setProvider(context, provider)
                    CloudConfigStore.setBaseUrl(context, provider.baseUrl)
                    CloudConfigStore.setSttModel(context, provider.sttModel)
                    CloudConfigStore.setTtsModel(context, provider.ttsModel)
                    currentProvider = provider
                    baseUrlDraft = provider.baseUrl
                    sttModelDraft = provider.sttModel
                    ttsModelDraft = provider.ttsModel
                },
                label = { Text(uiText.label(provider)) },
            )
        }
    }
    OutlinedTextField(
        value = baseUrlDraft,
        onValueChange = {
            baseUrlDraft = it
            CloudConfigStore.setBaseUrl(context, it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(uiText(UiR.string.ui_base_url_1dbd6)) },
        placeholder = { Text("https://…/v1") },
    )
    OutlinedTextField(
        value = sttModelDraft,
        onValueChange = {
            sttModelDraft = it
            CloudConfigStore.setSttModel(context, it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(uiText(UiR.string.ui_batch_stt_model_captions_3d989)) },
        placeholder = { Text("openai/whisper-1 / groq/whisper-large-v3-turbo") },
    )
    // Each explicit open refreshes the selected endpoint/account; no shared disk cache.
    var modelMenuOpen by remember(baseUrlDraft, keyRevision) { mutableStateOf(false) }
    var modelsLoading by remember(baseUrlDraft, keyRevision) { mutableStateOf(false) }
    var catalogModels by remember(baseUrlDraft, keyRevision) { mutableStateOf<List<SpeechModelCatalog.Model>>(emptyList()) }
    var catalogError by remember(baseUrlDraft, keyRevision) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(modelMenuOpen, baseUrlDraft, keyRevision) {
        if (modelMenuOpen) {
            modelsLoading = true
            catalogError = null
            catalogModels = emptyList()
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                OpenRouterModelsClient.fetch(baseUrlDraft, ApiKeyStore.getOpenAiKey(context))
            }
            when (result) {
                is OpenRouterModelsClient.Result.Models -> catalogModels = result.list
                is OpenRouterModelsClient.Result.Error -> catalogError = result.message
            }
            modelsLoading = false
        }
    }
    Text(
        uiText(UiR.string.ui_this_picker_changes_batch_stt_only_live_openai_cc_uses_gpt_live_t_3603e) +
            uiText(UiR.string.ui_live_translation_uses_gpt_realtime_translate_newest_creation_date_4eb89) +
            uiText(UiR.string.ui_the_api_does_not_provide_release_dates_or_openai_prices_9cb12),
        style = MaterialTheme.typography.bodySmall,
    )
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val pricingUrl = when (runCatching { java.net.URI(baseUrlDraft).host }.getOrNull()) {
        "api.openai.com" -> "https://developers.openai.com/api/docs/pricing"
        "openrouter.ai" -> "https://openrouter.ai/models?output_modalities=transcription"
        else -> null
    }
    if (pricingUrl != null) androidx.compose.material3.TextButton(onClick = { uriHandler.openUri(pricingUrl) }) {
        Text(uiText(UiR.string.ui_provider_pricing_and_billing_units_f48db))
    }
    Box {
        OutlinedButton(onClick = { modelMenuOpen = true }) {
            Text(uiText(UiR.string.ui_browse_speech_models_87929))
        }
        androidx.compose.material3.DropdownMenu(
            expanded = modelMenuOpen,
            onDismissRequest = { modelMenuOpen = false },
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 420.dp),
        ) {
            if (modelsLoading) Text(uiText(UiR.string.ui_loading_speech_models_f0902), modifier = Modifier.padding(12.dp))
            catalogError?.let {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (catalogModels.isEmpty() && !modelsLoading && catalogError == null) {
                Text(uiText(UiR.string.ui_no_recognized_speech_models_returned_custom_model_ids_can_still_b_dac29),
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            catalogModels.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    enabled = model.role.batchSelectable,
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(model.name)
                            Text(model.id, style = MaterialTheme.typography.labelSmall)
                            Text(uiText(UiR.string.ui_1_s_created_2_s_5f4ce, uiText.label(model.role), model.created),
                                style = MaterialTheme.typography.labelSmall)
                            Text(model.pricing, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    onClick = {
                        sttModelDraft = model.id
                        CloudConfigStore.setSttModel(context, model.id)
                        modelMenuOpen = false
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = ttsModelDraft,
        onValueChange = {
            ttsModelDraft = it
            CloudConfigStore.setTtsModel(context, it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(uiText(UiR.string.ui_tts_model_voice_54aa9)) },
        placeholder = { Text("tts-1") },
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = keyStoreError ?: if (keyStored) {
            uiText(UiR.string.ui_key_stored_encrypted_on_this_device_android_keystore_ef846) +
                uiText(UiR.string.ui_utterances_are_uploaded_only_while_an_overlay_session_runs_ac3e8)
        } else {
            uiText(UiR.string.ui_paste_the_key_from_your_provider_e_g_platform_openai_com_api_3cb5d) +
                uiText(UiR.string.ui_keys_stored_encrypted_on_this_device_the_foss_build_of_this_81a93) +
                uiText(UiR.string.ui_app_has_no_network_permission_at_all_cf763)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (keyStoreError != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    }
    }
}

/** Key field + save/remove row for one streaming provider. */
@Composable
private fun StreamingKeyField(
    label: String,
    placeholder: String,
    stored: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val uiText = rememberUiText()

    var draft by remember { mutableStateOf("") }
    OutlinedTextField(
        value = draft,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        onValueChange = { draft = it },
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(if (stored) uiText(UiR.string.ui_key_stored_type_to_replace_d9e13) else placeholder) },
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                onSave(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
            Text(if (stored) uiText(UiR.string.ui_replace_key_a55aa) else uiText(UiR.string.ui_save_key_f5216))
        }
        if (stored) {
            OutlinedButton(onClick = onRemove) {
                Text(uiText(UiR.string.ui_remove_e9639))
            }
        }
    }
}
