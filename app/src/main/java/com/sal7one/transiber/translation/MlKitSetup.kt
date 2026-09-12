package com.sal7one.transiber.translation

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
    Text("Google Translate · on-device. Download the spoken and target language packs on Wi-Fi. English is included.", style = MaterialTheme.typography.bodySmall)
    if (config.localTranslationModelId != TranslationOptions.ML_KIT) Button(onClick = { update { it.copy(localTranslationModelId = TranslationOptions.ML_KIT, localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) } }) {
        Text("Use ML Kit")
    }
    OutlinedButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        val language = LanguageCatalog.option(selected)
        Text("Language pack: ${language.label}")
    }
    if (menu) androidx.compose.ui.window.Dialog(onDismissRequest = { menu = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            LanguagePickerContent("Download a language pack", CaptionLanguageChoices(TranslationOptions.mlKitCodes - "en", "Choose a language to download or remove its pack. English is included."), selected,
                onSelect = { selected = it; menu = false }, onDismiss = { menu = false })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && selected !in packs, onClick = { scope.launch {
            busy = true; message = "Downloading $selected · waiting for Wi-Fi if needed"
            try { PlatformTranslation.download(selected); packs = PlatformTranslation.installed(); message = "$selected installed" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            finally { busy = false }
        } }) { Text("Download pack") }
        TextButton(enabled = !busy && selected in packs && selected != "en", onClick = { scope.launch {
            busy = true
            try { PlatformTranslation.remove(selected); packs = PlatformTranslation.installed(); message = "$selected removed" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            finally { busy = false }
        } }) { Text("Remove") }
    }
    Text("Installed: ${packs.sorted().joinToString { LanguageCatalog.option(it).englishName }}", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { details = !details }) { Text(if (details) "Hide language-pack details" else "About language packs") }
    if (details) Text("Non-English pairs translate through English. Packs live in ML Kit-managed app storage, separate from downloaded GGUF files.", style = MaterialTheme.typography.bodySmall)
    Text("Caption text stays on-device. Google collects SDK performance and usage metrics.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/terms") }) { Text("Google ML Kit privacy & terms") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it) }
}
