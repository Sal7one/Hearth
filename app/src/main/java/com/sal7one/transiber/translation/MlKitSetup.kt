package com.sal7one.transiber.translation

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.transiber.caption.*
import com.sal7one.common_jni.language.LanguageCatalog
import kotlinx.coroutines.*

@Composable
internal fun MlKitSetup(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    sourceLanguage: String = config.streamLanguage,
    targetLanguage: String = config.target.languageTag,
    onUse: (() -> Unit)? = null,
) {
    MlKitPackSetup(sourceLanguage, targetLanguage) {
        if (onUse != null) onUse() else update { it.copy(localTranslationModelId = TranslationOptions.ML_KIT,
            textTranslationProviderId = "local", localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) }
    }
}

/** Pair-specific repair lives next to the chooser, including the screen-reader chooser. No inference download. */
@Composable
internal fun MlKitPackSetup(source: String?, target: String?, onUse: () -> Unit) {
    val uiText = rememberUiText()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val revision by PlatformTranslation.revision.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var packs by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf(false) }
    var manage by rememberSaveable { mutableStateOf(false) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val readiness = mlKitPackReadiness(source, target, TranslationOptions.mlKitCodes, packs)
    val required = readiness.required
    val missing = readiness.missing
    var selected by rememberSaveable(source, target) { mutableStateOf(required.firstOrNull { it == target } ?: required.firstOrNull() ?: "ar") }
    val sourceAutomatic = readiness.automaticSource
    val pairSupported = readiness.supported
    fun labels(codes: Set<String>) = codes.joinToString { uiText.languageName(it) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, refresh) {
        packs = null
        try { packs = PlatformTranslation.installed() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = true; message = e.message ?: e.toString() }
    }
    suspend fun download(codes: Set<String>) {
        busy = true; failure = false
        message = uiText(if (wifiOnly) UiR.string.mlkit_pair_downloading_wifi else UiR.string.mlkit_pair_downloading, labels(codes))
        try {
            if (!sourceAutomatic && pairSupported && codes == missing)
                PlatformTranslation.downloadPair(checkNotNull(source), checkNotNull(target), wifiOnly)
            else for (code in codes) PlatformTranslation.download(code, wifiOnly)
            packs = PlatformTranslation.installed()
            message = uiText(UiR.string.mlkit_pair_downloaded, labels(codes))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = true; message = e.message ?: e.toString() }
        finally { busy = false; refresh++ }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(uiText(UiR.string.mlkit_pair_title), style = MaterialTheme.typography.titleSmall)
        Text(uiText(UiR.string.mlkit_pair_description), style = MaterialTheme.typography.bodySmall)
        when {
            packs == null -> Text(uiText(UiR.string.mlkit_pair_checking))
            !pairSupported -> Text(uiText(UiR.string.mlkit_pair_choose_languages), color = MaterialTheme.colorScheme.error)
            missing.isNotEmpty() -> Text(uiText(UiR.string.mlkit_pair_missing, labels(missing)))
            else -> Text(uiText(if (sourceAutomatic) UiR.string.mlkit_pair_auto_ready else UiR.string.mlkit_pair_ready), color = MaterialTheme.colorScheme.primary)
        }
        if (sourceAutomatic) Text(uiText(UiR.string.mlkit_pair_auto_source), style = MaterialTheme.typography.bodySmall)
        if (missing.isNotEmpty() && pairSupported) Button(enabled = !busy && packs != null,
            onClick = { scope.launch { download(missing) } }, modifier = Modifier.fillMaxWidth()) {
            Text(uiText(UiR.string.mlkit_pair_download_button, labels(missing)))
        }
        Button(enabled = !busy && readiness.ready, onClick = onUse,
            modifier = Modifier.fillMaxWidth()) { Text(uiText(UiR.string.ui_use_ml_kit_2fd85)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { refresh++ }, enabled = !busy) { Text(uiText(UiR.string.mlkit_pair_refresh)) }
            TextButton(onClick = { manage = !manage }) { Text(uiText(UiR.string.mlkit_pair_other_languages)) }
        }
        if (manage || missing.isNotEmpty()) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(wifiOnly, { wifiOnly = it }, enabled = !busy)
            Text(uiText(UiR.string.mlkit_pair_wifi_only))
        }
        if (manage) {
            OutlinedButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_language_pack_1_s_efe52, uiText.languageLabel(LanguageCatalog.option(selected))))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && packs != null && selected !in packs.orEmpty(),
                    onClick = { scope.launch { download(setOf(selected)) } }) { Text(uiText(UiR.string.ui_download_pack_5d6f8)) }
                TextButton(enabled = !busy && selected in packs.orEmpty() && selected != "en", onClick = { scope.launch {
                    busy = true; failure = false; message = null
                    try { PlatformTranslation.remove(selected); packs = PlatformTranslation.installed(); message = uiText(UiR.string.ui_1_s_removed_80296, uiText.languageName(selected)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = true; message = e.message ?: e.toString() }
                    finally { busy = false; refresh++ }
                } }) { Text(uiText(UiR.string.ui_remove_e9639)) }
            }
            packs?.let { Text(uiText(UiR.string.ui_installed_1_s_758c8, labels(it)), style = MaterialTheme.typography.bodySmall) }
            Text(uiText(UiR.string.ui_non_english_pairs_translate_through_english_packs_live_in_ml_kit_7239f), style = MaterialTheme.typography.bodySmall)
            Text(uiText(UiR.string.ui_caption_text_stays_on_device_google_collects_sdk_performance_and_8e899), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/terms") }) { Text(uiText(UiR.string.ui_google_ml_kit_privacy_terms_e6bc6)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    }
    if (menu) androidx.compose.ui.window.Dialog(onDismissRequest = { menu = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            LanguagePickerContent(uiText(UiR.string.ui_download_a_language_pack_64f85),
                CaptionLanguageChoices(TranslationOptions.mlKitCodes - "en", uiText(UiR.string.ui_choose_a_language_to_download_or_remove_its_pack_english_is_inclu_265bd)), selected,
                onSelect = { selected = it; menu = false }, onDismiss = { menu = false })
        }
    }
}
