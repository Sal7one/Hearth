package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.ui.components.ModelSourceLinks
import com.sal7one.transiber.ui.components.ModelLibraryDisclosure
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.R

@Composable
internal fun VoiceModelCard(
    title: String, runtime: String, coverage: String, license: String, url: String,
    onError: (String?) -> Unit, filesUrl: String? = null, licenseUrl: String? = null,
) {
    val text = rememberUiText()
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    ModelLibraryDisclosure(text(R.string.settings_flow_model_info), expanded, { expanded = !expanded }) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(runtime, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(coverage, style = MaterialTheme.typography.bodyMedium)
            Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelSourceLinks(url, filesUrl, licenseUrl, onError = { onError(it) })
        }
    }
    }
}
