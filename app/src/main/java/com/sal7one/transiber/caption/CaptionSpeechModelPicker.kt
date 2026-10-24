package com.sal7one.transiber.caption

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.runtime.LocalModelPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.sal7one.transiber.R as UiR

private data class CaptionSpeechPickerInventory(
    val models: List<LocalSpeechModel> = emptyList(),
    val errors: List<String> = emptyList(),
    val checking: Boolean = true,
)

/** Exact installed checkpoint selection, without opening native runtimes or hashing model weights. */
@Composable
internal fun CaptionSpeechModelPicker(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    onManage: () -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val engine = config.effectiveEngine
    val backend = engine.speechBackend ?: return
    if (!LocalModelPolicy.allowed(context)) {
        com.sal7one.transiber.ui.components.LocalModelPolicyNotice()
        return
    }
    val uiText = rememberUiText()
    val bindings by ModelSourceBindings.checks.collectAsStateWithLifecycle()
    val resumed = rememberCaptionSetupResumeRevision()
    val modelId = config.modelId
    val request = listOf(context, backend, modelId, bindings, resumed)
    // Never label a changed/missing selection with the previously resolved checkpoint.
    var inventory by remember(request) { mutableStateOf(CaptionSpeechPickerInventory()) }
    var expanded by rememberSaveable(backend, modelId) { mutableStateOf(false) }
    LaunchedEffect(request) {
        inventory = withContext(Dispatchers.IO) {
            try {
                val snapshot = LocalSpeechModels(File(context.filesDir, "speech-models")).inspect()
                val models = snapshot.models.filter { it.profile.backend == backend }
                val missing = if (modelId.isNotBlank() && models.none { it.id == modelId }) {
                    snapshot.issues.firstOrNull { it.startsWith("$modelId:") }
                        ?: "Selected ${engine.label} package is unavailable: $modelId. Choose an installed model or open Models."
                } else null
                CaptionSpeechPickerInventory(models, (listOfNotNull(missing) + snapshot.issues).distinct(), checking = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                CaptionSpeechPickerInventory(errors = listOf(error.message ?: error.toString()), checking = false)
            }
        }
    }
    // Blank means the same first installed package selected by the production loader.
    // A nonblank missing ID must never fall back to another checkpoint's name or language.
    val selected = if (modelId.isBlank()) inventory.models.firstOrNull() else inventory.models.firstOrNull { it.id == modelId }
    fun label(model: LocalSpeechModel) = "${model.profile.label} · ${model.id.takeLast(6)}"
    Column(Modifier.fillMaxWidth().testTag("caption-speech-model-picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(uiText(UiR.string.ui_speech_model_3c5f0), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled && !inventory.checking && inventory.models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("caption-speech-model-menu"),
            contentPadding = PaddingValues(12.dp)) {
            Text(when {
                inventory.checking -> uiText(UiR.string.ui_loading_speech_models_f0902)
                selected != null -> label(selected)
                else -> uiText(UiR.string.ui_choose_a_model_fefb3)
            }, Modifier.weight(1f), maxLines = 2)
            Text(if (expanded) "−" else "+")
        }
        if (expanded && !inventory.checking) {
            // Inline bounded choices work inside the service's existing overlay window too.
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                inventory.models.forEach { model ->
                    val chosen = selected?.id == model.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .testTag("caption-speech-model-${model.id}")
                        .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = {
                            expanded = false
                            if (model.id != modelId) update { current ->
                                if (current.effectiveEngine == engine) activateSpeechModel(current, model.profile, model.id) else current
                            }
                        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen, onClick = null, enabled = enabled)
                        Text(label(model), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (!inventory.checking && inventory.models.isEmpty() && inventory.errors.isEmpty()) {
            Text(uiText(UiR.string.ui_none_installed_yet_download_and_install_below_or_import_an_existi_69b24),
                style = MaterialTheme.typography.bodySmall)
        }
        inventory.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onManage, enabled = enabled, modifier = Modifier.testTag("caption-speech-model-manage")) {
            Text(uiText(UiR.string.ui_choose_or_download_speech_models_34085))
        }
    }
}
