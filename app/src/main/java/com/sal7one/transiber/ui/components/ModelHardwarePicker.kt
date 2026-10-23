package com.sal7one.transiber.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.ModelHardwareFilter

@Composable
internal fun ModelHardwarePicker(filter: ModelHardwareFilter, onChange: (String) -> Unit) {
    val text = rememberUiText()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelFilterPicker(text(R.string.model_hardware_filter), filter.key, listOf(
            ModelHardwareFilter.ALL.key to text(R.string.model_hardware_all),
            ModelHardwareFilter.GPU.key to text(R.string.model_hardware_gpu),
            ModelHardwareFilter.VULKAN.key to text(R.string.translation_acceleration_vulkan),
            ModelHardwareFilter.OPENCL.key to text(R.string.translation_acceleration_opencl),
            ModelHardwareFilter.CPU_ONLY.key to text(R.string.model_hardware_cpu),
        ), onChange)
        if (filter != ModelHardwareFilter.ALL) Text(text(if (filter == ModelHardwareFilter.CPU_ONLY)
            R.string.model_hardware_cpu_hint else R.string.model_hardware_gpu_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ModelGpuPaths(paths: Set<String>) {
    if (paths.isEmpty()) return
    val text = rememberUiText()
    Text(text(R.string.model_hardware_paths, paths.joinToString(" · ") { path -> text(when (path) {
        "vulkan" -> R.string.translation_acceleration_vulkan
        else -> R.string.translation_acceleration_opencl
    }) }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}
