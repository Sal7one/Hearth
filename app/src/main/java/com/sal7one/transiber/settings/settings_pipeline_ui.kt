package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.runtime.LocalModelPolicy
import com.sal7one.transiber.translation.ConversationTranslationSettings
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.translation.TranslatorChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A directory reads the saved pipeline. Only explicit switches edit their own stage. */
@Composable
internal fun SettingsPipelineUi(onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onTranslationModels: () -> Unit) {
    val text = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localAllowed = LocalModelPolicy.allowed(context)
    var config by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var translationEditor by rememberSaveable { mutableStateOf<SettingsLocation?>(null) }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect { config = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    fun update(change: (CaptionOverlayConfig) -> CaptionOverlayConfig) {
        scope.launch {
            try { CaptionConfigStore.update(context, change); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }
    Text(text(R.string.settings_flow_pipeline), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    Text(text(R.string.settings_flow_independent), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val current = config
    if (current == null) {
        if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        val speechLocation = if (current.effectiveEngine == CaptionEngineChoice.CLOUD) SettingsLocation.CLOUD else SettingsLocation.LOCAL
        val translator = ConversationTranslationSettings.provider(current.textTranslationProviderId)?.label
            ?: current.localTranslationModelId.takeIf { it.isNotBlank() }?.let(TranslationOptions::label)
            ?: text(R.string.settings_flow_integrated_translation)
        val translationLocation = if (current.textTranslationProviderId in setOf("", "local")) SettingsLocation.LOCAL else SettingsLocation.CLOUD
        val voiceRemote = when (current.speakerChoice) {
            CaptionSpeakerChoice.CLOUD -> true
            CaptionSpeakerChoice.CUSTOM -> com.sal7one.transiber.voice.VoiceSettings.customBackend(context) == "remote"
            CaptionSpeakerChoice.SHARED -> com.sal7one.transiber.voice.VoiceSettings.choice(context).backend == "remote"
            else -> false
        }
        val voiceLocation = if (voiceRemote) SettingsLocation.CLOUD else SettingsLocation.LOCAL
        SettingsStageCard(SettingsFeature.SPEECH, text(R.string.settings_flow_speech), Icons.Default.Mic,
            if (speechLocation == SettingsLocation.CLOUD) text.label(CloudConfigStore.sttMode(context)) else text.label(current.engine),
            speechLocation, localAllowed, onFeature)
        SettingsStageCard(SettingsFeature.TRANSLATION, text(R.string.settings_flow_translation), Icons.Default.Translate,
            translator, translationLocation, localAllowed, onFeature,
            checked = current.mode == CaptionMode.TRANSLATE, onCheckedChange = { enabled ->
                update { it.withCaptionMode(if (enabled) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS) }
            }, onEdit = { translationEditor = it })
        translationEditor?.let { location ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { translationEditor = null }) { Text(text(R.string.ui_close_bbfa7)) }
                    TranslatorChooser(current.textTranslationProviderId.ifBlank { "local" }, current.localTranslationModelId,
                        text(R.string.settings_flow_caption_translation_scope), location = location,
                        source = current.streamLanguage, target = current.target.languageTag,
                        onModels = onTranslationModels,
                        onSelect = { provider, model -> update { it.copy(textTranslationProviderId = provider, localTranslationModelId = model) } })
                }
            }
        }
        SettingsStageCard(SettingsFeature.VOICES, text(R.string.settings_flow_voice), Icons.Default.RecordVoiceOver,
            text.label(current.speakerChoice), voiceLocation, localAllowed, onFeature,
            checked = current.speakCaptions, onCheckedChange = { enabled -> update { it.copy(speakCaptions = enabled) } })
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SettingsStageCard(feature: SettingsFeature, title: String, icon: ImageVector, summary: String,
    preferred: SettingsLocation, localAllowed: Boolean, onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    checked: Boolean? = null, onCheckedChange: ((Boolean) -> Unit)? = null,
    onEdit: ((SettingsLocation) -> Unit)? = null) {
    val text = rememberUiText()
    val locations = settingsStageLocations(feature, ByokPolicy.FEATURE_BYOK, localAllowed)
    val destination = settingsStageDestination(preferred, feature, ByokPolicy.FEATURE_BYOK, localAllowed)
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (checked != null && onCheckedChange != null) Switch(checked, onCheckedChange,
                    modifier = Modifier.semantics { contentDescription = title })
            }
            if (destination != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onEdit?.invoke(destination) ?: onFeature(destination, feature) }) {
                    Text(text(R.string.settings_flow_edit, text.label(destination)))
                }
                locations.filter { it != destination }.forEach { location ->
                    TextButton(onClick = { onEdit?.invoke(location) ?: onFeature(location, feature) }) { Text(text(R.string.settings_flow_other, text.label(location))) }
                }
            } else Text(text(R.string.settings_flow_unavailable), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
