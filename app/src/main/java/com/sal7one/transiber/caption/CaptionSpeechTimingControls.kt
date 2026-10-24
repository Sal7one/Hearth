package com.sal7one.transiber.caption

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.speech.SpeechStreamingKind
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.runtime.LocalModelPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class CaptionTimingSelection(val profile: SpeechProfile? = null, val error: String? = null)

/** Only the model whose tuning was saved may be restarted; selection can change during an IO write. */
internal fun CaptionOverlayConfig.afterSpeechTimingSaved(engine: CaptionEngineChoice, selectedModelId: String): CaptionOverlayConfig =
    if (effectiveEngine == engine && modelId == selectedModelId) copy(speechSelectionRevision = speechSelectionRevision + 1)
    else this

/** Everyday timing controls for the exact active package, shared by Home and the floating overlay. */
@Composable
internal fun CaptionSpeechTimingControls(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val engine = config.effectiveEngine
    if (engine.speechBackend == null || !LocalModelPolicy.allowed(context)) return
    val bindings by ModelSourceBindings.checks.collectAsStateWithLifecycle()
    val resumed = rememberCaptionSetupResumeRevision()
    val modelId = config.modelId
    val request = listOf(engine, modelId, bindings, resumed)
    // Never show the previous profile's controls while a different package is being resolved.
    var selection by remember(request) { mutableStateOf(CaptionTimingSelection()) }
    LaunchedEffect(request) {
        selection = withContext(Dispatchers.IO) {
            try {
                val profile = LocalSpeechModels(File(context.filesDir, "speech-models")).select(engine, modelId).profile
                CaptionTimingSelection(profile.takeIf { it.capabilities.streaming == SpeechStreamingKind.UTTERANCE_WINDOWED })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { CaptionTimingSelection(error = error.message ?: error.toString()) }
        }
    }
    selection.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    selection.profile?.let { profile ->
        key(profile.id, engine, modelId) {
            SpeechWindowTuningSection(profile, onApplied = {
                update { it.afterSpeechTimingSaved(engine, modelId) }
            }, enabled = enabled)
        }
    }
}
