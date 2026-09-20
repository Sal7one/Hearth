package com.sal7one.transiber.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.*

@Composable
fun ModelsScreen(onDownloads: () -> Unit = {}, onVoices: () -> Unit = {}, initialSection: String = "Speech") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(CaptionOverlayConfig()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect {
            config = it
            if (!loaded) { loaded = true }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
        scope.launch { try { CaptionConfigStore.update(context, transform); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() } }
    }
    LaunchedEffect(Unit) {
        val browse = context.getSharedPreferences("translation-browser",0)
        if (browse.getBoolean("open",false)) { section = "Translation"; browse.edit().remove("open").apply() }
    }
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Speech", "Translation", "Camera", "Voices").forEach { group ->
                FilterChip(selected = section == group, onClick = { section = group }, label = { Text(group) })
            }
        }
        pages.SaveableStateProvider(section) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                when (section) {
                    "Voices" -> { Text("Android voices and local Supertonic 3, with model downloads and import.");Button(onClick=onVoices){Text("Voices & read aloud")} }
                    "Speech" -> {
                        com.sal7one.transiber.settings.SettingsSpeechLocalUi(config, update)
                    }
                    "Translation" -> {
                        Text("Local translation", style = MaterialTheme.typography.titleLarge)
                        Text("Language packs or text models for on-device translation.", style = MaterialTheme.typography.bodySmall)
                        com.sal7one.transiber.settings.SettingsTranslateLocalUi(config, { change -> update { current -> change(current).copy(mode = current.mode, localTranslationEnabled = current.localTranslationEnabled) } }, includeLegacy = true, showCaptionControls = false)
                    }
                    "Camera" -> com.sal7one.transiber.settings.SettingsOcrLocalUi(onDownloads)

                }
                if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = onDownloads) { Text("View downloads & installation progress") }
            }
        }
    }
}
