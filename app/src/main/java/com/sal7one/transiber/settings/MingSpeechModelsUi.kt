package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.i18n.rememberUiText

/** Hosted speech templates only. Selecting a model never changes a server URL, key or MT stage. */
@Composable
internal fun MingSpeechModelsUi(selected: String, onSelect: (String) -> Unit) {
    if (!ByokPolicy.FEATURE_BYOK) return
    val text = rememberUiText()
    val uri = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    val models = listOf("asr-tiny", "audio-tiny", "audio-flash")
    Box {
        TextButton(onClick = { expanded = true }) { Text(text(R.string.ming_speech_models)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            models.forEach { suffix ->
                val id = "inclusionAI/Ming-3.0-$suffix"
                DropdownMenuItem(text = { Text("Ming-3.0-$suffix") }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
    models.firstOrNull { selected == "inclusionAI/Ming-3.0-$it" }?.let { suffix ->
        Text(text(if (suffix == "asr-tiny") R.string.ming_asr_hosted_detail else R.string.ming_audio_hosted_detail),
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uri.openUri("https://huggingface.co/inclusionAI/Ming-3.0-$suffix") }) {
            Text(text(R.string.ming_official_model_card))
        }
    }
}
