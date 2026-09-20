package com.sal7one.transiber.translation

import com.sal7one.transiber.settings.SettingsTranslateCloudUi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.settings.SettingsLocation
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The same chooser works inside an Activity sheet or the service's existing overlay window. */
@Composable
internal fun TranslatorChooser(
    providerId: String,
    localId: String,
    scopeLabel: String,
    source: String? = null,
    target: String? = null,
    enabled: Boolean = true,
    onSelect: (provider: String, localModel: String) -> Unit,
    onModels: () -> Unit,
    automaticLabel: String? = null,
    location: SettingsLocation? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by ConversationTranslationSettings.revision.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(location != null) }
    var cloudTab by rememberSaveable { mutableStateOf(providerId !in setOf("", "local")) }
    val showCloud = location?.let { it == SettingsLocation.CLOUD } ?: cloudTab
    var editing by remember { mutableStateOf<TextTranslationProvider?>(null) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var applying by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(providerId,localId) { applied=false }
    LaunchedEffect(expanded) { if(expanded && location == null)cloudTab=providerId !in setOf("", "local") }
    LaunchedEffect(expanded, localId) {
        try { installed = withContext(Dispatchers.IO) { LocalTranslationModels(File(context.filesDir, "translation-models")).installed().map { it.id }.toSet() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: e.toString() }
    }
    val label = if (providerId.isBlank() && automaticLabel != null) automaticLabel
        else ConversationTranslationSettings.provider(providerId)?.label ?: TranslationOptions.label(localId)
    fun pair(supports: (String, String) -> Boolean): String = when {
        source == null || target == null -> ""
        source in setOf("auto", "model", "und", "mul") -> " · choose a spoken language, or use language reported by speech recognition"
        source == target -> " · same language; original text only"
        supports(source, target) -> " · $source → $target supported"
        else -> " · $source → $target unavailable; change languages"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (location == null) OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(12.dp)) {
            Text("Translator · $label", Modifier.weight(1f))
            Text(if (expanded) "−" else "+")
        }
        if (expanded) {
            Text(scopeLabel, style = MaterialTheme.typography.bodySmall)
            Text("Local model and saved connections are shared.", style = MaterialTheme.typography.bodySmall)
            if (providerId.isNotBlank() && (location == null || (providerId == "local") == (location == SettingsLocation.LOCAL))) {
                OutlinedButton(enabled=enabled && !applying, onClick={
                    applying=true;failure=null
                    scope.launch { try {
                        ConversationTranslationSettings.useEverywhere(context,providerId,localId);applied=true
                    } catch(e: CancellationException){throw e}
                    catch(e: Exception){failure=e.message ?: e.toString()}
                    finally {applying=false} }
                }) { Text(if(applying)"Saving…" else "Use this translator across Hearth") }
                Text("Applies to every feature. Restart active camera/reading sessions to use the new choice.",style=MaterialTheme.typography.bodySmall)
                if(applied)Text("Translator saved for all features.",style=MaterialTheme.typography.bodySmall)
            }
            if (automaticLabel != null) TranslatorRow(automaticLabel, "Keep the speech engine’s integrated or legacy translation route.", providerId.isBlank(), enabled) {
                onSelect("", localId); expanded = location != null
            }
            if(ByokPolicy.FEATURE_BYOK && location == null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!showCloud,onClick={cloudTab=false;editing=null},label={Text("On device")})
                FilterChip(selected=showCloud,onClick={cloudTab=true;editing=null},label={Text("Cloud / server")})
            }
            if(!showCloud || !ByokPolicy.FEATURE_BYOK) {
            Text("On device", style = MaterialTheme.typography.titleMedium)
            Text("Original text stays on your phone.", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup()) {
                if (PlatformTranslation.available) TranslatorRow(TranslationOptions.label(TranslationOptions.ML_KIT),
                    "Language packs required · manage/download in Models" + pair { a,b -> a in TranslationOptions.mlKitCodes && b in TranslationOptions.mlKitCodes },
                    providerId == "local" && localId == TranslationOptions.ML_KIT, enabled) {
                    onSelect("local", TranslationOptions.ML_KIT); expanded = location != null
                }
                TranslationCatalog.models.sortedWith(compareByDescending<com.sal7one.common_jni.translation.TranslationModelSpec> { providerId=="local" && it.id==localId }.thenByDescending { it.id in installed }).forEach { model ->
                    val ready = model.id in installed
                    TranslatorRow(model.label,
                        "${if (ready) "Installed" else "Download or import"} · ${model.bytes / 1_048_576} MiB · ${model.sourceLanguages.size} languages" + pair(model::supports),
                        providerId == "local" && localId == model.id, enabled,
                        actionLabel = if (ready) null else "Set up") {
                        if (ready) { onSelect("local", model.id); expanded = location != null } else {
                            context.getSharedPreferences("translation-browser",0).edit().putString("model",model.id).putBoolean("open",true).apply(); onModels()
                        }
                    }
                }
            }
            TextButton(onClick = { context.getSharedPreferences("translation-browser",0).edit().putBoolean("open",true).apply(); onModels() }, enabled = enabled) { Text("Model downloads, imports & language packs") }
            }
            if (ByokPolicy.FEATURE_BYOK && showCloud) {
                Text("Cloud / self-hosted", style = MaterialTheme.typography.titleMedium)
                Text("Recognized or typed text is sent to the chosen server. Your speech provider is separate.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    TextTranslationProvider.entries.forEach { provider ->
                        val capabilities = remember(revision, provider) { ConversationTranslationSettings.capabilities(context, provider) }
                        val ready = capabilities != null && (provider == TextTranslationProvider.LIBRETRANSLATE || ConversationTranslationSettings.hasKey(context, provider))
                        TranslatorRow(provider.label,
                            if (ready) "Connection saved · ${capabilities!!.sourceLanguages.size} source / ${capabilities.targetLanguages.size} target languages" + pair(capabilities::supports)
                            else if (provider == TextTranslationProvider.LIBRETRANSLATE) "Connect your server · key depends on server" else "Set up API key & check languages",
                            providerId == provider.id, enabled, actionLabel = if (ready) null else "Set up") {
                            if (ready) { onSelect(provider.id, localId); expanded = location != null } else editing = provider
                        }
                        if (ready) TextButton(onClick = { editing = provider }, enabled = enabled) { Text("Manage ${provider.label}") }
                    }
                }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            editing?.let { provider ->
                HorizontalDivider()
                TextButton(onClick = { editing = null }) { Text("Close connection setup") }
                key(provider) { SettingsTranslateCloudUi(initialProvider = provider.id, allowSelection = false) }
            }
        }
    }
}

@Composable
private fun TranslatorRow(title: String, detail: String, selected: Boolean, enabled: Boolean,
                          actionLabel: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .selectable(selected = selected, enabled = enabled, role = if(actionLabel == null) Role.RadioButton else Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actionLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}
