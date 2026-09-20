package com.sal7one.transiber.translation

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.*
import com.sal7one.common_jni.language.LanguageCatalog
import kotlinx.coroutines.*

@Composable
internal fun MlKitSetup(config: CaptionOverlayConfig, update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit) {
    val uiText = rememberUiText()

    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var details by remember { mutableStateOf(false) }
    var packs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selected by remember { mutableStateOf(config.streamLanguage.takeIf { it in TranslationOptions.mlKitCodes } ?: "ru") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { packs = PlatformTranslation.installed() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: e.toString() }
    }
    Text(uiText(UiR.string.ui_google_translate_on_device_download_the_spoken_and_target_languag_235e1), style = MaterialTheme.typography.bodySmall)
    if (config.localTranslationModelId != TranslationOptions.ML_KIT) Button(onClick = { update { it.copy(localTranslationModelId = TranslationOptions.ML_KIT, localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) } }) {
        Text(uiText(UiR.string.ui_use_ml_kit_2fd85))
    }
    OutlinedButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        val language = LanguageCatalog.option(selected)
        Text(uiText(UiR.string.ui_language_pack_1_s_efe52, uiText.languageLabel(language)))
    }
    if (menu) androidx.compose.ui.window.Dialog(onDismissRequest = { menu = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            LanguagePickerContent(uiText(UiR.string.ui_download_a_language_pack_64f85), CaptionLanguageChoices(TranslationOptions.mlKitCodes - "en", uiText(UiR.string.ui_choose_a_language_to_download_or_remove_its_pack_english_is_inclu_265bd)), selected,
                onSelect = { selected = it; menu = false }, onDismiss = { menu = false })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && selected !in packs, onClick = { scope.launch {
            busy = true; message = uiText(UiR.string.ui_downloading_1_s_waiting_for_wi_fi_if_needed_a5f4d, selected)
            try { PlatformTranslation.download(selected); packs = PlatformTranslation.installed(); message = uiText(UiR.string.ui_1_s_installed_feb78, selected) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            finally { busy = false }
        } }) { Text(uiText(UiR.string.ui_download_pack_5d6f8)) }
        TextButton(enabled = !busy && selected in packs && selected != "en", onClick = { scope.launch {
            busy = true
            try { PlatformTranslation.remove(selected); packs = PlatformTranslation.installed(); message = uiText(UiR.string.ui_1_s_removed_80296, selected) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            finally { busy = false }
        } }) { Text(uiText(UiR.string.ui_remove_e9639)) }
    }
    Text(uiText(UiR.string.ui_installed_1_s_758c8, packs.sorted().joinToString { uiText.languageName(it) }), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { details = !details }) { Text(if (details) uiText(UiR.string.ui_hide_language_pack_details_f673e) else uiText(UiR.string.ui_about_language_packs_25633)) }
    if (details) Text(uiText(UiR.string.ui_non_english_pairs_translate_through_english_packs_live_in_ml_kit_7239f), style = MaterialTheme.typography.bodySmall)
    Text(uiText(UiR.string.ui_caption_text_stays_on_device_google_collects_sdk_performance_and_8e899), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/terms") }) { Text(uiText(UiR.string.ui_google_ml_kit_privacy_terms_e6bc6)) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it) }
}
