package com.sal7one.transiber.byok

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun CloudSourceLinks(mode: CloudConfigStore.SttMode? = null) {
    val uiText = rememberUiText()

    if (!ByokPolicy.FEATURE_BYOK) return
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    val sources = listOf(
        Triple(CloudConfigStore.SttMode.STREAMING_OPENAI, uiText(UiR.string.ui_openai_live_speech_translation_d7b5e), "https://platform.openai.com/docs/guides/realtime"),
        Triple(CloudConfigStore.SttMode.STREAMING_SONIOX, uiText(UiR.string.ui_soniox_v5_speech_translation_6a8ec), "https://soniox.com/docs/api-reference/stt/websocket-api"),
        Triple(CloudConfigStore.SttMode.STREAMING_ELEVENLABS, uiText(UiR.string.ui_scribe_v2_speech_recognition_5c97a), "https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime"),
        Triple(CloudConfigStore.SttMode.STREAMING_DEEPGRAM, uiText(UiR.string.ui_deepgram_speech_recognition_3a549), "https://developers.deepgram.com/docs/models-languages-overview"),
        Triple(CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI, uiText(UiR.string.ui_assemblyai_speech_recognition_4e78b), "https://www.assemblyai.com/docs/api-reference/overview"),
        Triple(CloudConfigStore.SttMode.BATCH, uiText(UiR.string.ui_openai_compatible_batch_audio_d02f8), "https://platform.openai.com/docs/guides/speech-to-text"),
    )
    sources.filter { mode == null || it.first == mode }.forEach { (_, label, url) ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { try { uri.openUri(url) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text(uiText(UiR.string.ui_1_s_documentation_b0463, label.substringBefore(" ·"))) }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
