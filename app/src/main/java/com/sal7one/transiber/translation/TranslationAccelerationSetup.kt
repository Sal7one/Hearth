package com.sal7one.transiber.translation

import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.translation.TranslationAcceleration
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText

/** Shared GGUF policy; changing it never changes the selected model, speech recognizer or TTS. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TranslationAccelerationSetup() {
    val context = LocalContext.current
    val uiText = rememberUiText()
    val revision by ConversationTranslationSettings.revision.collectAsState()
    val selected = remember(revision) { ConversationTranslationSettings.acceleration(context) }
    val arm64 = remember { Process.is64Bit() && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(uiText(R.string.translation_acceleration_title), style = MaterialTheme.typography.titleSmall)
                    Text(uiText(accelerationLabel(selected)), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(uiText(if (expanded)
                    R.string.translation_acceleration_hide else R.string.translation_acceleration_change)) }
            }
            if (!arm64) {
                Text(uiText(R.string.translation_acceleration_arm64_only), style = MaterialTheme.typography.bodySmall)
                if (selected != TranslationAcceleration.CPU) TextButton(onClick = {
                    try { ConversationTranslationSettings.setAcceleration(context, TranslationAcceleration.CPU); error = null }
                    catch (e: Exception) { error = e.message ?: e.toString() }
                }) { Text(uiText(R.string.translation_acceleration_use_cpu)) }
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationAcceleration.entries.forEach { acceleration ->
                        FilterChip(selected == acceleration, onClick = {
                            try { ConversationTranslationSettings.setAcceleration(context, acceleration); error = null }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                        }, enabled = acceleration == TranslationAcceleration.CPU || arm64,
                            label = { Text(uiText(accelerationLabel(acceleration))) })
                    }
                }
                Text(uiText(when (selected) {
                    TranslationAcceleration.CPU -> R.string.translation_acceleration_cpu_note
                    TranslationAcceleration.VULKAN -> R.string.translation_acceleration_gpu_note
                    TranslationAcceleration.OPENCL -> R.string.translation_acceleration_opencl_note
                }), style = MaterialTheme.typography.bodySmall)
                Text(uiText(R.string.translation_acceleration_scope), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun accelerationLabel(acceleration: TranslationAcceleration): Int = when (acceleration) {
    TranslationAcceleration.CPU -> R.string.translation_acceleration_cpu
    TranslationAcceleration.VULKAN -> R.string.translation_acceleration_vulkan
    TranslationAcceleration.OPENCL -> R.string.translation_acceleration_opencl
}
