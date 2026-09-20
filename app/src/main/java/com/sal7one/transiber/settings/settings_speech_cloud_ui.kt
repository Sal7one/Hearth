package com.sal7one.transiber.settings

import com.sal7one.transiber.byok.*

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
    if (!ByokPolicy.FEATURE_BYOK) return
    val context = LocalContext.current
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
    Text("Cloud provider / connection", style = MaterialTheme.typography.labelMedium)
    // FlowRow: four mode chips would squash the last one flat in a Row.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CloudConfigStore.SttMode.entries.forEach { mode ->
            androidx.compose.material3.FilterChip(
                selected = currentSttMode == mode,
                onClick = {
                    CloudConfigStore.setSttMode(context, mode)
                    currentSttMode = mode
                    onModeChange(mode)
                },
                label = { Text(mode.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when (currentSttMode) {
            CloudConfigStore.SttMode.STREAMING_SONIOX -> "Soniox stt-rt-v5: streaming CC and optional integrated translation. Source and translated text are retained separately."
            CloudConfigStore.SttMode.STREAMING_ELEVENLABS -> "ElevenLabs Scribe v2 Realtime: streaming CC with optional spoken-language hint. Enable a local translator for translation. Auto source cannot yet route local translation on this adapter."
            CloudConfigStore.SttMode.BATCH ->
                "Batch uploads chunks of audio and returns one result per " +
                    "round-trip — slowest, but works with any OpenAI-compatible " +
                    "endpoint (OpenAI, OpenRouter, or Groq via a custom URL)."
            CloudConfigStore.SttMode.STREAMING_DEEPGRAM ->
                "Deepgram streams audio over a WebSocket and emits interim text " +
                    "live; Nova-3 is among the lowest-latency ASR on the market."
            CloudConfigStore.SttMode.STREAMING_OPENAI ->
                "OpenAI Realtime transcription (gpt-live-transcribe) streams " +
                    "deltas over a WebSocket — uses the OpenAI key above."
            CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI ->
                "AssemblyAI's realtime v3 WebSocket streams interim + final " +
                    "turns; Universal models are multilingual by default."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (currentSttMode == CloudConfigStore.SttMode.BATCH || currentSttMode == CloudConfigStore.SttMode.STREAMING_OPENAI) {
    Text(
        "Cloud engine · your API key",
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = keyDraft,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        onValueChange = { keyDraft = it },
        singleLine = true,
        placeholder = {
            Text(if (keyStored) "Key stored — type to replace" else "sk-…")
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
                    keyStoreError = "Could not store the key on this device — " +
                        (storageFailure ?: "storage failed") +
                        ". Nothing was saved."
                } else {
                    keyStoreError = if (
                        ApiKeyStore.storageMode(context) == ApiKeyStore.StorageMode.BASIC
                    ) {
                        "Stored with basic protection — this device's Keystore " +
                            "was unavailable (" + (storageFailure ?: "unknown") + ")."
                    } else {
                        null
                    }
                }
                refresh()
            },
            enabled = keyDraft.isNotBlank(),
        ) {
            Text(if (keyStored) "Replace key" else "Save key")
        }
        if (keyStored) {
            OutlinedButton(
                onClick = {
                    val removed = ApiKeyStore.setOpenAiKey(context, "")
                    keyStoreError = if (removed) null else ApiKeyStore.lastFailure
                    refresh()
                },
            ) {
                Text("Remove")
            }
        }
    }
    }
    CloudSourceLinks(currentSttMode)
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_SONIOX) {
        StreamingKeyField("Soniox API key", "https://console.soniox.com", sonioxStored,
            { ApiKeyStore.setSonioxKey(context, it); sonioxStored = ApiKeyStore.getSonioxKey(context).isNotBlank() },
            { ApiKeyStore.setSonioxKey(context, ""); sonioxStored = false })
    }
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_ELEVENLABS) {
        StreamingKeyField("ElevenLabs API key", "https://elevenlabs.io/app/settings/api-keys", elevenStored,
            { ApiKeyStore.setElevenLabsKey(context, it); elevenStored = ApiKeyStore.getElevenLabsKey(context).isNotBlank() },
            { ApiKeyStore.setElevenLabsKey(context, ""); elevenStored = false })
    }
    if (currentSttMode == CloudConfigStore.SttMode.STREAMING_DEEPGRAM) {
        Spacer(Modifier.height(8.dp))
        StreamingKeyField(
            label = "Deepgram API key",
            placeholder = "Paste the key from console.deepgram.com",
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
            label = "AssemblyAI API key",
            placeholder = "Paste the key from assemblyai.com/app/account",
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
        TextButton(onClick = { extraOptions = !extraOptions }) { Text(if (extraOptions) "Hide batch & voice options" else "Optional batch & cloud voice settings") }
    }
    if (currentSttMode == CloudConfigStore.SttMode.BATCH || extraOptions) {
        Text("These endpoint and model settings affect batch uploads and optional cloud voice. Streaming uses the provider selected above.", style = MaterialTheme.typography.bodySmall)
    // ── Provider & model ─────────────────────────────────────────────
    // Endpoint-agnostic: OpenAI keys go to OpenAI, OpenRouter keys to
    // OpenRouter, anything OpenAI-compatible to a custom URL. The model
    // ids are editable — the cloud engine reads them at session start.
    Spacer(Modifier.height(10.dp))
    Text("Provider", style = MaterialTheme.typography.labelMedium)
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
                label = { Text(provider.label) },
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
        label = { Text("Base URL") },
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
        label = { Text("Batch STT model (captions)") },
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
        "This picker changes batch STT only. Live OpenAI CC uses gpt-live-transcribe; " +
            "live translation uses gpt-realtime-translate. Newest creation date first " +
            "(the API does not provide release dates or OpenAI prices).",
        style = MaterialTheme.typography.bodySmall,
    )
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val pricingUrl = when (runCatching { java.net.URI(baseUrlDraft).host }.getOrNull()) {
        "api.openai.com" -> "https://developers.openai.com/api/docs/pricing"
        "openrouter.ai" -> "https://openrouter.ai/models?output_modalities=transcription"
        else -> null
    }
    if (pricingUrl != null) androidx.compose.material3.TextButton(onClick = { uriHandler.openUri(pricingUrl) }) {
        Text("Provider pricing and billing units")
    }
    Box {
        OutlinedButton(onClick = { modelMenuOpen = true }) {
            Text("Browse speech models")
        }
        androidx.compose.material3.DropdownMenu(
            expanded = modelMenuOpen,
            onDismissRequest = { modelMenuOpen = false },
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 420.dp),
        ) {
            if (modelsLoading) Text("Loading speech models…", modifier = Modifier.padding(12.dp))
            catalogError?.let {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (catalogModels.isEmpty() && !modelsLoading && catalogError == null) {
                Text("No recognized speech models returned. Custom model IDs can still be entered manually.",
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            catalogModels.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    enabled = model.role.batchSelectable,
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(model.name)
                            Text(model.id, style = MaterialTheme.typography.labelSmall)
                            Text("${model.role.label} · Created ${model.created}",
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
        label = { Text("TTS model (voice)") },
        placeholder = { Text("tts-1") },
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = keyStoreError ?: if (keyStored) {
            "Key stored, encrypted on this device (Android Keystore). " +
                "Utterances are uploaded only while an overlay session runs."
        } else {
            "Paste the key from your provider (e.g. platform.openai.com → API " +
                "keys). Stored encrypted on this device; the FOSS build of this " +
                "app has no network permission at all."
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
    var draft by remember { mutableStateOf("") }
    OutlinedTextField(
        value = draft,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        onValueChange = { draft = it },
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(if (stored) "Key stored — type to replace" else placeholder) },
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
            Text(if (stored) "Replace key" else "Save key")
        }
        if (stored) {
            OutlinedButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}
