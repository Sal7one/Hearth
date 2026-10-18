package com.sal7one.transiber.caption

import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import kotlinx.coroutines.launch

/** Advanced per-engine control. It never changes the selected model or other pipeline stages. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SpeechAccelerationSetup(engine: CaptionEngineChoice, onApplied: () -> Unit) {
    if (SpeechAccelerationStore.choices(engine).size == 1) return
    val context = LocalContext.current
    val ui = rememberUiText()
    val scope = rememberCoroutineScope()
    val selected by remember(engine) { SpeechAccelerationStore.selected(context, engine) }.collectAsState("cpu")
    val arm64 = remember { Process.is64Bit() && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" }
    var error by remember(engine) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui(R.string.speech_acceleration_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechAccelerationStore.choices(engine).forEach { id ->
                    FilterChip(selected == id, onClick = {
                        scope.launch {
                            saving = true
                            try { SpeechAccelerationStore.save(context, engine, id); onApplied(); error = null }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                            finally { saving = false }
                        }
                    }, enabled = !saving && (id == "cpu" || arm64), label = {
                        Text(ui(when (id) {
                            "vulkan" -> R.string.translation_acceleration_vulkan
                            "opencl" -> R.string.translation_acceleration_opencl
                            else -> R.string.translation_acceleration_cpu
                        }))
                    })
                }
            }
            Text(ui(if (arm64) R.string.speech_acceleration_note else R.string.translation_acceleration_arm64_only),
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
