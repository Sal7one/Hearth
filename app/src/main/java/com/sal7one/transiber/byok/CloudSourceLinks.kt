package com.sal7one.transiber.byok

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun CloudSourceLinks(mode: CloudConfigStore.SttMode? = null) {
    if (!ByokPolicy.FEATURE_BYOK) return
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    val sources = listOf(
        Triple(CloudConfigStore.SttMode.STREAMING_OPENAI, "OpenAI · live speech / translation", "https://platform.openai.com/docs/guides/realtime"),
        Triple(CloudConfigStore.SttMode.STREAMING_SONIOX, "Soniox v5 · speech + translation", "https://soniox.com/docs/api-reference/stt/websocket-api"),
        Triple(CloudConfigStore.SttMode.STREAMING_ELEVENLABS, "Scribe v2 · speech recognition", "https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime"),
        Triple(CloudConfigStore.SttMode.STREAMING_DEEPGRAM, "Deepgram · speech recognition", "https://developers.deepgram.com/docs/models-languages-overview"),
        Triple(CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI, "AssemblyAI · speech recognition", "https://www.assemblyai.com/docs/api-reference/overview"),
        Triple(CloudConfigStore.SttMode.BATCH, "OpenAI-compatible · batch audio", "https://platform.openai.com/docs/guides/speech-to-text"),
    )
    sources.filter { mode == null || it.first == mode }.forEach { (_, label, url) ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { try { uri.openUri(url) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text("${label.substringBefore(" ·")} documentation") }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
