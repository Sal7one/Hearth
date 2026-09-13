package com.sal7one.transiber.translation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionOverlayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by the conversation sheet and cloud setup. Keys never enter saved Compose state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationTranslationSetup(onModels: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by ConversationTranslationSettings.revision.collectAsState()
    val config by remember { CaptionConfigStore.config(context) }.collectAsState(initial = CaptionOverlayConfig())
    val selected = remember(revision) { ConversationTranslationSettings.selected(context) }
    var editing by remember { mutableStateOf(ConversationTranslationSettings.provider(selected)) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf<TranslationHttpTransport?>(null) }
    DisposableEffect(Unit) { onDispose { transport?.close() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Conversation translation", style = MaterialTheme.typography.titleLarge)
        Text("Used in Conversation and Face to face. Speech recognition and overlay settings stay separate.", style = MaterialTheme.typography.bodySmall)
        Text("Using: ${ConversationTranslationSettings.label(context, config.localTranslationModelId)}", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = editing == null, enabled = !working, onClick = { editing = null; status = null }, label = { Text("On device") })
            if (ByokPolicy.FEATURE_BYOK) TextTranslationProvider.entries.forEach { provider ->
                FilterChip(selected = editing == provider, enabled = !working, onClick = { editing = provider; status = null }, label = { Text(provider.label) })
            }
        }
        val provider = editing
        if (provider == null) {
            Text(TranslationOptions.label(config.localTranslationModelId))
            Text("Text stays on this phone. Install or choose a model in Models.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { ConversationTranslationSettings.select(context, ConversationTranslationSettings.LOCAL) }, enabled = selected != ConversationTranslationSettings.LOCAL) { Text(if (selected == ConversationTranslationSettings.LOCAL) "Using on-device translation" else "Use on-device translation") }
            OutlinedButton(onClick = onModels) { Text("Choose local model") }
        } else key(provider) {
            var endpoint by remember { mutableStateOf(ConversationTranslationSettings.endpoint(context, provider)) }
            var region by remember { mutableStateOf(ConversationTranslationSettings.region(context, provider)) }
            var secret by remember { mutableStateOf("") }
            val savedKey = remember(revision) { ConversationTranslationSettings.hasKey(context, provider) }
            val capabilities = remember(revision) { ConversationTranslationSettings.capabilities(context, provider) }
            val dirty = secret.isNotBlank() || endpoint.trim() != ConversationTranslationSettings.endpoint(context, provider) || region.trim() != ConversationTranslationSettings.region(context, provider)
            Text(when (provider) {
                TextTranslationProvider.GOOGLE -> "Google Cloud Translation Basic v2. Requires your Google Cloud API key and an enabled Translation API."
                TextTranslationProvider.AZURE -> "Microsoft Azure Translator (the official Microsoft translation API). Use its resource key and region when required."
                TextTranslationProvider.DEEPL -> "DeepL API Free or Pro key. Use api-free.deepl.com/v2 for Free or api.deepl.com/v2 for Pro."
                TextTranslationProvider.LIBRETRANSLATE -> "Open-source LibreTranslate. Choose your own HTTPS server; a key is optional only if that server allows it. libretranslate.com requires a key."
            }, style = MaterialTheme.typography.bodyMedium)
            Text("Finalized text is sent to this provider. Audio is handled separately by your selected speech model. Provider charges or limits may apply.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(endpoint, { endpoint = it }, enabled = !working, label = { Text("API base URL · HTTPS") }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), singleLine = true)
            if (provider == TextTranslationProvider.AZURE) OutlinedTextField(region, { region = it }, enabled = !working, label = { Text("Resource region · blank for global") }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), singleLine = true)
            OutlinedTextField(secret, { secret = it }, enabled = !working, label = { Text(if (savedKey) "Replace saved API key" else if (provider == TextTranslationProvider.LIBRETRANSLATE) "API key · if required" else "API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 2.dp), singleLine = true)
            if (savedKey) Text("A key is encrypted on this device. Leave the field empty to keep it.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !working, onClick = {
                    working = true; status = null; failure = false
                    val nextEndpoint = endpoint.trim(); val nextRegion = region.trim(); val replacement = secret.takeIf { it.isNotBlank() }
                    scope.launch {
                        try {
                            val found = withContext(Dispatchers.IO) {
                                val saved = if (replacement == null) ConversationTranslationSettings.connection(context, provider).key else ""
                                val next = CloudTranslationConnection(provider, nextEndpoint, replacement ?: saved, nextRegion)
                                // Validate the credential/region syntax even for unauthenticated language discovery.
                                CloudTranslationProtocol.translateRequest(next, "Connection setup", "en", "ar")
                                val requests = CloudTranslationProtocol.languageRequests(next)
                                ConversationTranslationSettings.save(context, provider, nextEndpoint, nextRegion, replacement)
                                val active = TranslationHttpTransport(); transport = active
                                try { CloudTranslationProtocol.parseLanguages(provider, requests.map { active.execute(it, next.key) }) }
                                finally { active.close(); transport = null }
                            }
                            ConversationTranslationSettings.saveCapabilities(context, provider, found)
                            secret = ""; status = "Languages loaded: ${found.sourceLanguages.size} source languages, ${found.targetLanguages.size} target languages."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = true; status = e.message ?: e.toString() }
                        finally { working = false }
                    }
                }) { Text(if (working) "Checking…" else "Save & check languages") }
                if (working) TextButton(onClick = { transport?.close() }) { Text("Cancel check") }
                else TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.sourceUrl))) }) { Text("Key & API help") }
            }
            if (capabilities != null && !dirty) {
                Text("${capabilities.sourceLanguages.size} source · ${capabilities.targetLanguages.size} target languages. The picker uses this server's supported directions. Language discovery does not verify translation quota or billing.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !working && selected != provider.id, onClick = { ConversationTranslationSettings.select(context, provider.id) }) { Text(if (selected == provider.id) "Using ${provider.label}" else "Use ${provider.label}") }
            } else Text("Check this connection to load its supported languages before using it.", style = MaterialTheme.typography.bodySmall)
            if (savedKey) TextButton(enabled = !working, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { ConversationTranslationSettings.forget(context, provider) }; secret = ""; status = "Key removed. On-device translation is used if this connection was selected."; failure = false }
                    catch (e: Exception) { status = e.message ?: e.toString(); failure = true }
                }
            }, modifier = Modifier.semantics { contentDescription = "Remove ${provider.label} saved key" }) { Text("Remove saved key") }
        }
        status?.let { Text(it, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp)) }
    }
}
