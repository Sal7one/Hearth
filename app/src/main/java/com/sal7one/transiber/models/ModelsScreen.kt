package com.sal7one.transiber.models

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

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
    val uiText = rememberUiText()

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
                FilterChip(selected = section == group, onClick = { section = group }, label = { Text(uiText.modelSection(group)) })
            }
        }
        pages.SaveableStateProvider(section) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                when (section) {
                    "Voices" -> { Text(uiText(UiR.string.ui_android_voices_and_local_supertonic_3_with_model_downloads_and_im_193d5));Button(onClick=onVoices){Text(uiText(UiR.string.ui_voices_read_aloud_64e95))} }
                    "Speech" -> {
                        com.sal7one.transiber.settings.SettingsSpeechLocalUi(config, update)
                    }
                    "Translation" -> {
                        Text(uiText(UiR.string.ui_local_translation_010cc), style = MaterialTheme.typography.titleLarge)
                        Text(uiText(UiR.string.ui_language_packs_or_text_models_for_on_device_translation_0d656), style = MaterialTheme.typography.bodySmall)
                        com.sal7one.transiber.settings.SettingsTranslateLocalUi(config, { change -> update { current -> change(current).copy(mode = current.mode, localTranslationEnabled = current.localTranslationEnabled) } }, includeLegacy = true, showCaptionControls = false)
                    }
                    "Camera" -> com.sal7one.transiber.settings.SettingsOcrLocalUi(onDownloads)

                }
                if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = onDownloads) { Text(uiText(UiR.string.ui_view_downloads_installation_progress_f57b2)) }
            }
        }
    }
}
