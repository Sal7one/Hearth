package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import com.sal7one.transiber.translation.*

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by the conversation sheet and cloud setup. Keys never enter saved Compose state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTranslateCloudUi(initialProvider: String? = null, allowSelection: Boolean = true) {
    val uiText = rememberUiText()
    val advanced = com.sal7one.transiber.preferences.rememberSetupMode() == com.sal7one.transiber.preferences.SetupMode.ADVANCED

    if (!ByokPolicy.FEATURE_BYOK) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by ConversationTranslationSettings.revision.collectAsState()
    val config by remember { CaptionConfigStore.config(context) }.collectAsState(initial = CaptionOverlayConfig())
    val selected = remember(revision) { ConversationTranslationSettings.selected(context) }
    var editing by remember { mutableStateOf(ConversationTranslationSettings.provider(initialProvider ?: selected) ?: TextTranslationProvider.entries.first()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf<TranslationHttpTransport?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { checkJob?.cancel(); transport?.close() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsLayout.cardGap)) {
        SettingsHeading(uiText(UiR.string.ui_translation_connections_4a282),
            uiText(UiR.string.ui_one_connection_library_for_captions_typed_text_conversations_came_a2ee8))
        if (allowSelection) Text(uiText(UiR.string.ui_conversation_typed_text_use_1_s_dc9f7, ConversationTranslationSettings.label(context, ConversationTranslationSettings.localModel(context, config.localTranslationModelId))), style = MaterialTheme.typography.titleSmall)
        com.sal7one.transiber.ui.components.ModelFilterPicker(uiText(UiR.string.ui_provider_7ceee), editing.id,
            TextTranslationProvider.entries.map { it.id to it.label },
            { id -> editing = TextTranslationProvider.entries.first { it.id == id }; status = null }, enabled = !working)
        val provider = editing
        key(provider) {
            var endpoint by remember { mutableStateOf(ConversationTranslationSettings.endpoint(context, provider)) }
            var region by remember { mutableStateOf(ConversationTranslationSettings.region(context, provider)) }
            var secret by remember { mutableStateOf("") }
            var details by rememberSaveable { mutableStateOf(false) }
            val savedKey = remember(revision) { ConversationTranslationSettings.hasKey(context, provider) }
            val sameEndpoint = CloudTranslationProtocol.sameEndpoint(provider, endpoint, ConversationTranslationSettings.endpoint(context, provider))
            val capabilities = remember(revision) { ConversationTranslationSettings.capabilities(context, provider) }
            val dirty = secret.isNotBlank() || endpoint.trim() != ConversationTranslationSettings.endpoint(context, provider) || region.trim() != ConversationTranslationSettings.region(context, provider)
            if (advanced || details) Text(when (provider) {
                TextTranslationProvider.GOOGLE -> uiText(UiR.string.ui_google_cloud_translation_basic_v2_requires_your_google_cloud_api_5f73d)
                TextTranslationProvider.AZURE -> uiText(UiR.string.ui_microsoft_azure_translator_the_official_microsoft_translation_api_b8ead)
                TextTranslationProvider.DEEPL -> uiText(UiR.string.ui_deepl_api_free_or_pro_key_use_api_free_deepl_com_v2_for_free_or_a_62829)
                TextTranslationProvider.LIBRETRANSLATE -> uiText(UiR.string.ui_open_source_libretranslate_choose_your_own_https_server_a_key_is_1b625)
            }, style = MaterialTheme.typography.bodyMedium)
            Text(uiText(UiR.string.ui_finalized_text_is_sent_to_this_provider_audio_is_handled_separate_d490d), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details = !details }) { Text(uiText(UiR.string.settings_flow_connection_options)) }
            if (advanced || details || provider == TextTranslationProvider.LIBRETRANSLATE)
                OutlinedTextField(endpoint, { endpoint = it }, enabled = !working, label = { Text(uiText(UiR.string.ui_api_base_url_https_43d49)) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
            if (provider == TextTranslationProvider.AZURE) OutlinedTextField(region, { region = it }, enabled = !working, label = { Text(uiText(UiR.string.ui_resource_region_blank_for_global_64f7b)) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
            OutlinedTextField(secret, { secret = it }, enabled = !working, label = { Text(if (savedKey && sameEndpoint) uiText(UiR.string.ui_replace_saved_api_key_c2dc3) else if (provider == TextTranslationProvider.LIBRETRANSLATE) uiText(UiR.string.ui_api_key_if_required_49a3c) else uiText(UiR.string.ui_api_key_cf678)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 2.dp), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
            if (savedKey && sameEndpoint) Text(uiText(UiR.string.ui_a_key_is_encrypted_on_this_device_leave_the_field_empty_to_keep_i_2c064), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !working, onClick = {
                    working = true; status = null; failure = false
                    val nextEndpoint = endpoint.trim(); val nextRegion = region.trim(); val replacement = secret.takeIf { it.isNotBlank() } ?: if (sameEndpoint) null else ""
                    checkJob = scope.launch {
                        try {
                            val found = withContext(Dispatchers.IO) {
                                ConversationTranslationSettings.checkAndSave(context, provider, nextEndpoint, nextRegion, replacement) { next ->
                                    val requests = CloudTranslationProtocol.languageRequests(next)
                                    val active = TranslationHttpTransport(); transport = active
                                    try { CloudTranslationProtocol.parseLanguages(provider, requests.map { active.execute(it, next.key) }) }
                                    finally { active.close(); transport = null }
                                }
                            }
                            secret = ""; status = uiText(UiR.string.ui_languages_loaded_1_s_source_languages_2_s_target_languages_5a0db, found.sourceLanguages.size, found.targetLanguages.size)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = true; status = e.message ?: e.toString() }
                        finally { working = false }
                    }
                }) { Text(if (working) uiText(UiR.string.ui_checking_820d6) else uiText(UiR.string.ui_save_check_languages_7cd32)) }
                if (working) TextButton(onClick = { checkJob?.cancel(); transport?.close() }) { Text(uiText(UiR.string.ui_cancel_check_82097)) }
                else TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.sourceUrl))) }
                    catch (e: Exception) { failure = true; status = e.message ?: e.toString() }
                }) { Text(uiText(UiR.string.ui_key_api_help_3e3bb)) }
            }
            if (capabilities != null && !dirty) {
                Text(uiText(UiR.string.ui_1_s_source_2_s_target_languages_the_picker_uses_this_server_s_sup_04d3b, capabilities.sourceLanguages.size, capabilities.targetLanguages.size), style = MaterialTheme.typography.bodySmall)
                if (allowSelection) Button(enabled = !working && selected != provider.id, onClick = { ConversationTranslationSettings.select(context, provider.id) }) { Text(if (selected == provider.id) uiText(UiR.string.ui_using_1_s_922b9, provider.label) else uiText(UiR.string.ui_use_1_s_5cc45, provider.label)) }
            } else Text(uiText(UiR.string.ui_check_this_connection_to_load_its_supported_languages_before_usin_2b734), style = MaterialTheme.typography.bodySmall)
            if (savedKey) TextButton(enabled = !working, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { ConversationTranslationSettings.forget(context, provider) }; secret = ""; status = uiText(UiR.string.ui_key_removed_on_device_translation_is_used_if_this_connection_was_59cd6); failure = false }
                    catch (e: Exception) { status = e.message ?: e.toString(); failure = true }
                }
            }, modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_remove_1_s_saved_key_71dd0, provider.label) }) { Text(uiText(UiR.string.ui_remove_saved_key_303fd)) }
        }
        status?.let { Text(it, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp)) }
    }
}
