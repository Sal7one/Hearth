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
    var expanded by remember { mutableStateOf(config.localTranslationModelId == TranslationOptions.ML_KIT) }
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
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text("ML Kit · lightweight language packs") }
    if (!expanded) return
    Text("On-device translation by Google Translate. Non-English pairs use English in between. Packs are managed inside the app by ML Kit, separately from GGUF files.")
    Button(onClick = { update { it.copy(localTranslationModelId = TranslationOptions.ML_KIT, localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) } }) {
        Text(if (config.localTranslationModelId == TranslationOptions.ML_KIT) "ML Kit selected" else "Use ML Kit")
    }
    Text("Download the spoken and target language packs on Wi-Fi before starting. English needs no separate pack.")
    Box {
        OutlinedButton(onClick = { menu = true }, enabled = !busy) { Text("Language pack: $selected") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            TranslationOptions.mlKitCodes.sorted().forEach { code ->
                DropdownMenuItem(text = { Text("${java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault())} · $code${if (code in packs) " · Installed" else ""}") }, onClick = { selected = code; menu = false })
            }
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
    Text("Available packs: ${packs.sorted().joinToString()}")
    Text("Caption text stays on-device. Google collects SDK performance and usage metrics.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/terms") }) { Text("Google ML Kit privacy & terms") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it) }
}
