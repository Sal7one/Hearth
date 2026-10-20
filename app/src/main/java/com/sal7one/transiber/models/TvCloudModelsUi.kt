package com.sal7one.transiber.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.settings.*
import com.sal7one.transiber.ui.components.LocalModelPolicyNotice

/** TV exposes connection configuration without constructing any local model pickers. */
@Composable
internal fun TvCloudModelsUi(initialSection: String) {
    val text = rememberUiText()
    var section by rememberSaveable { mutableStateOf(initialSection.takeIf { it in listOf("Speech", "Translation", "Voices") } ?: "Speech") }
    var language by rememberSaveable { mutableStateOf("ar") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text(R.string.tv_model_connections), style = MaterialTheme.typography.headlineSmall)
        LocalModelPolicyNotice()
        if (!ByokPolicy.FEATURE_BYOK) {
            Text(text(R.string.tv_offline_connections_unavailable))
            return@Column
        }
        val sections = listOf("Speech", "Translation", "Voices")
        TabRow(sections.indexOf(section)) {
            sections.forEach { name -> Tab(section == name, onClick = { section = name; error = null },
                text = { Text(text.modelSection(name)) }) }
        }
        when (section) {
            "Speech" -> SettingsSpeechCloudUi()
            "Translation" -> SettingsTranslateCloudUi()
            "Voices" -> SettingsVoiceCloudUi(language, { language = it }, { error = it }, {})
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
