package com.sal7one.transiber.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.languageName
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.ui.components.ModelLibraryDisclosure
import com.sal7one.transiber.ui.components.ModelStatusLabel
import com.sal7one.transiber.ui.components.downloadStage

@Composable
internal fun GroupedDownloadCard(group: DownloadQueueGroup, busy: Boolean, expanded: Boolean,
    onToggle: () -> Unit, onAction: () -> Unit, voiceReady: Boolean? = null) {
    val text = rememberUiText()
    val pair = group.modelId?.let(MarianPackage::find)
    val speech = group.modelId?.let(com.sal7one.transiber.models.SpeechArtifactCatalog::find)
    val title = group.voiceFamily?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.find(it)?.label ?: it }
        ?: speech?.label ?: pair?.let { "Marian · ${text.languageName(it.source)} → ${text.languageName(it.target)}" }
        ?: com.sal7one.common_jni.translation.Ct2Catalog.find(group.modelId.orEmpty())?.label.orEmpty()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val stage = if (group.voiceFamily != null && voiceReady == true && !group.active) DownloadStage.INSTALLED
                else group.stage?.let { if (group.voiceFamily != null && voiceReady == false && it == DownloadStage.INSTALLED) DownloadStage.READY else it }
            stage?.let { ModelStatusLabel(text.downloadStage(it)) }
            if (!(group.voiceFamily != null && voiceReady == true && !group.active))
                Text(text(R.string.library_partial_download, group.completed, group.required.size), style = MaterialTheme.typography.bodyMedium)
            if (group.voiceFamily != null && (voiceReady == true || group.installedRecord != null)) Text(text(
                if (voiceReady == true) R.string.library_voice_ready else R.string.library_voice_setup_remaining), style = MaterialTheme.typography.bodySmall)
            if (group.active) {
                val fraction = group.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            group.failure?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(text(when {
                    group.voiceFamily != null && !group.active && (voiceReady == true || group.installedRecord != null) -> R.string.library_open_voices
                    group.installedRecord != null -> if (speech != null) R.string.speech_artifact_select else R.string.library_use_translator
                    group.active -> R.string.library_pause
                    group.stage == DownloadStage.PAUSED -> R.string.ui_resume_download
                    group.stage == DownloadStage.FAILED -> R.string.ui_retry_download_install_e5444
                    group.stage == DownloadStage.READY -> R.string.ui_install_downloaded_model_d458c
                    else -> R.string.translation_browser_download_remaining
                }))
            }
            ModelLibraryDisclosure(text(R.string.library_file_actions), expanded, onToggle) {
                Text(text(R.string.library_files, group.rows.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
