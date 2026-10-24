package com.sal7one.transiber.caption

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.rememberSetupMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.sal7one.transiber.R as UiR

/** Reads and writes the same per-profile DataStore values consumed by CaptionEngineController. */
internal interface SpeechTuningSettings {
    fun observe(profileId: String): Flow<SpeechTuning?>
    suspend fun save(profileId: String, tuning: SpeechTuning?)
}

private class StoredSpeechTuningSettings(private val context: Context) : SpeechTuningSettings {
    override fun observe(profileId: String) = SpeechTuningStore.tuning(context, profileId)
    override suspend fun save(profileId: String, tuning: SpeechTuning?) {
        if (tuning == null || tuning.isStandard) SpeechTuningStore.clear(context, profileId)
        else SpeechTuningStore.save(context, profileId, tuning)
    }
}

/** Explicit timing choices for windowed recognition; selecting one never changes the model or language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeechWindowTuningSection(
    profile: SpeechProfile,
    onApplied: () -> Unit,
    enabled: Boolean = true,
    settings: SpeechTuningSettings? = null,
) {
    val uiText = rememberUiText()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val defaultSettings = remember(context) { StoredSpeechTuningSettings(context) }
    val store = settings ?: defaultSettings
    val advanced = rememberSetupMode() == SetupMode.ADVANCED
    var stored by remember(profile.id, store) { mutableStateOf<SpeechTuning?>(null) }
    var ready by remember(profile.id, store) { mutableStateOf(false) }
    var saving by remember(profile.id, store) { mutableStateOf(false) }
    var error by remember(profile.id, store) { mutableStateOf<String?>(null) }
    var saveJob by remember(profile.id, store) { mutableStateOf<Job?>(null) }
    DisposableEffect(profile.id, store) { onDispose { saveJob?.cancel() } }
    LaunchedEffect(profile.id, store) {
        try {
            store.observe(profile.id).collect {
                stored = it
                ready = true
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: failure.toString() }
    }
    val current = stored ?: SpeechTuning.STANDARD
    var editingCustom by remember(profile.id, store) { mutableStateOf(false) }
    var windowMs by remember(profile.id, current) { mutableStateOf(current.maxUtteranceMs) }
    var silenceMs by remember(profile.id, current) { mutableStateOf(current.silenceMs) }
    val editable = enabled && ready && !saving

    fun persist(tuning: SpeechTuning?) {
        if (!enabled || !ready || saving) return
        saving = true
        error = null
        saveJob = scope.launch {
            try {
                store.save(profile.id, tuning)
                currentCoroutineContext().ensureActive()
                stored = tuning?.takeUnless { it.isStandard }
                onApplied()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: failure.toString() }
            finally { saving = false }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("speech-timing-${profile.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(uiText(UiR.string.ui_caption_delivery_for_1_s_47c31, profile.label), style = MaterialTheme.typography.titleSmall)
        Text(uiText(UiR.string.ui_caption_delivery_note_5a1e0), style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SpeechTuning.STANDARD to UiR.string.ui_tuning_standard_2c94d,
                SpeechTuning.RESPONSIVE to UiR.string.ui_tuning_responsive_7b36e,
                SpeechTuning.EAGER to UiR.string.ui_tuning_eager_9f58c,
            ).forEach { (preset, label) ->
                FilterChip(
                    selected = current.matches(preset),
                    enabled = editable,
                    onClick = { editingCustom = false; if (!current.matches(preset)) persist(preset) },
                    label = { Text(uiText(label)) },
                    modifier = Modifier.testTag("speech-timing-${profile.id}-preset-${preset.maxUtteranceMs}"),
                )
            }
        }
        if (!ready && error == null) Text(uiText(UiR.string.setup_preferences_loading), style = MaterialTheme.typography.bodySmall)
        if (saving) Text(uiText(UiR.string.ui_saving_56a22), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("speech-timing-error")) }
        if (ready) {
            Text(uiText(UiR.string.ui_tuning_window_slider_8e02f, String.format(java.util.Locale.ROOT, "%.1f", current.maxUtteranceMs / 1000f)),
                style = MaterialTheme.typography.labelMedium)
            Text(uiText(UiR.string.ui_tuning_silence_slider_31d5b, String.format(java.util.Locale.ROOT, "%.1f", current.silenceMs / 1000f)),
                style = MaterialTheme.typography.labelMedium)
        }
        if (advanced || current.preset == null) {
            TextButton(enabled = editable, onClick = { editingCustom = !editingCustom },
                modifier = Modifier.testTag("speech-timing-${profile.id}-custom")) {
                Text(uiText(UiR.string.ui_tuning_custom_4b71a))
            }
        }
        if (editingCustom && (advanced || current.preset == null)) {
            Slider(
                value = windowMs.toFloat(), enabled = editable,
                onValueChange = { windowMs = (it.toInt() / 20) * 20 },
                onValueChangeFinished = { if (SpeechTuning.isValid(windowMs, silenceMs)) persist(SpeechTuning(windowMs, silenceMs)) },
                valueRange = 1000f..15000f,
                steps = (15000 - 1000) / 20 - 1,
                modifier = Modifier.testTag("speech-timing-${profile.id}-window"),
            )
            Text(uiText(UiR.string.ui_tuning_window_slider_8e02f, String.format(java.util.Locale.ROOT, "%.1f", windowMs / 1000f)),
                style = MaterialTheme.typography.labelMedium)
            Slider(
                value = silenceMs.toFloat(), enabled = editable,
                onValueChange = { silenceMs = (it.toInt() / 20) * 20 },
                onValueChangeFinished = { if (SpeechTuning.isValid(windowMs, silenceMs)) persist(SpeechTuning(windowMs, silenceMs)) },
                valueRange = 200f..2000f,
                steps = (2000 - 200) / 20 - 1,
                modifier = Modifier.testTag("speech-timing-${profile.id}-silence"),
            )
            Text(uiText(UiR.string.ui_tuning_silence_slider_31d5b, String.format(java.util.Locale.ROOT, "%.1f", silenceMs / 1000f)),
                style = MaterialTheme.typography.labelMedium)
            if (!SpeechTuning.isValid(windowMs, silenceMs)) {
                Text(uiText(UiR.string.ui_tuning_invalid_bounds_9d27a), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (stored != null) {
            TextButton(enabled = editable, onClick = { editingCustom = false; persist(null) },
                modifier = Modifier.testTag("speech-timing-${profile.id}-reset")) {
                Text(uiText(UiR.string.ui_tuning_reset_a903c))
            }
        }
    }
}
