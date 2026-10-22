package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.hearth.tts.ming.MingCatalog
import com.sal7one.hearth.tts.ming.MingModels
import com.sal7one.hearth.tts.ming.MingSpeechEngine
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.ui.components.ModelStatusLabel
import com.sal7one.transiber.voice.LocalVoiceFamilies
import com.sal7one.transiber.voice.MingLocalFeature
import com.sal7one.transiber.voice.VoiceChoice
import com.sal7one.transiber.voice.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Experimental Ming-3.0 local TTS settings. The gate is off by default; availability and bundle
 * readiness are read from the real loader/files, never assumed. There is no streaming or native
 * cancel, and the measured phone RTF came from an app_process run, not an in-app JNI run.
 */
@Composable
internal fun SettingsVoiceMingUi(
    choice: VoiceChoice,
    save: (VoiceChoice) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    onError: (String?) -> Unit,
    onImport: () -> Unit,
    onDownloads: () -> Unit,
    revision: Int,
    onRevision: () -> Unit,
) {
    val text = rememberUiText()
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(MingLocalFeature.enabled(context)) }
    val runtimeAvailable = remember(enabled) { enabled && MingSpeechEngine.available() }
    val runtimeReason = remember(enabled) { if (enabled) MingSpeechEngine.availabilityReason() else "" }
    val models = remember { MingModels(LocalVoiceFamilies.directory(context, MingCatalog.FAMILY)) }
    var ready by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(MingCatalog.assets.size) }

    LaunchedEffect(revision, enabled) {
        if (!enabled) return@LaunchedEffect
        try {
            val snapshot = withContext(Dispatchers.IO) { models.missing().size to models.ready() }
            missing = snapshot.first
            ready = snapshot.second
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error.message ?: error.toString())
        }
    }
    LaunchedEffect(Unit) { if (MingCatalog.language(language) == null) onLanguage("en") }

    VoiceModelCard(
        MingCatalog.DISPLAY_NAME,
        text(R.string.ming_local_runtime),
        text(R.string.ming_local_coverage),
        MingCatalog.LICENSE,
        MingCatalog.SOURCE,
        onError,
        filesUrl = MingCatalog.filesUrl,
        licenseUrl = MingCatalog.licenseUrl,
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text(R.string.ming_experimental_title), style = MaterialTheme.typography.titleSmall)
            Text(text(R.string.ming_experimental_detail), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = { value ->
            enabled = value
            MingLocalFeature.setEnabled(context, value)
            onError(null)
            onRevision()
        })
    }

    if (!enabled) {
        Text(text(R.string.ming_enable_first), style = MaterialTheme.typography.bodySmall)
        return
    }

    if (!runtimeAvailable) {
        Text(text(R.string.ming_no_runtime, runtimeReason), color = MaterialTheme.colorScheme.error)
    }
    if (choice.voice !in MingCatalog.voices) {
        OutlinedButton(onClick = { save(choice.copy(voice = VoiceSettings.familyVoice(context, MingCatalog.FAMILY))) }) {
            Text(text(R.string.ming_select_voice))
        }
    }
    VoiceMenu(text(R.string.ming_voice), choice.voice, MingCatalog.voices.map { it to it }) { save(choice.copy(voice = it)) }
    VoiceMenu(
        text(R.string.magpie_language),
        language,
        MingCatalog.languages.sorted().map { it to LanguageCatalog.option(it).nativeName },
    ) { onLanguage(it) }

    if (ready) ModelStatusLabel(text(R.string.ming_ready))
    else Text(text(R.string.ming_install_note, missing, MingCatalog.assets.size), style = MaterialTheme.typography.bodySmall)
    Text(text(R.string.ming_limits), style = MaterialTheme.typography.bodySmall)

    VoicePackageActions(
        MingCatalog.FAMILY,
        ready,
        runtimeAvailable,
        revision,
        text(R.string.ming_download),
        text(R.string.ming_import),
        onError,
        onImport,
        onDownloads,
        onRevision,
    )
}
