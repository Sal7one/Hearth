package com.sal7one.transiber.translation

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.models.ModelEngineType
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
    val uiText = rememberUiText()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by ConversationTranslationSettings.revision.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(location != null) }
    var cloudTab by rememberSaveable { mutableStateOf(providerId !in setOf("", "local")) }
    val showCloud = location?.let { it == SettingsLocation.CLOUD } ?: cloudTab
    var editing by remember { mutableStateOf<TextTranslationProvider?>(null) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val registered by remember(context) { ModelRegistry.getInstance(context).registeredModels }.collectAsState()
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
        source in setOf("auto", "model", "und", "mul") -> uiText(UiR.string.ui_choose_a_spoken_language_or_use_language_reported_by_speech_recog_0b9be)
        source == target -> uiText(UiR.string.ui_same_language_original_text_only_03af2)
        supports(source, target) -> uiText(UiR.string.ui_1_s_2_s_supported_5000e, source, target)
        else -> uiText(UiR.string.ui_1_s_2_s_unavailable_change_languages_49ad7, source, target)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (location == null) OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(12.dp)) {
            Text(uiText(UiR.string.ui_translator_1_s_7405d, label), Modifier.weight(1f))
            Text(if (expanded) "−" else "+")
        }
        if (expanded) {
            Text(scopeLabel, style = MaterialTheme.typography.bodySmall)
            Text(uiText(UiR.string.ui_local_model_and_saved_connections_are_shared_22351), style = MaterialTheme.typography.bodySmall)
            if (providerId.isNotBlank() && (location == null || (providerId == "local") == (location == SettingsLocation.LOCAL))) {
                OutlinedButton(enabled=enabled && !applying, onClick={
                    applying=true;failure=null
                    scope.launch { try {
                        ConversationTranslationSettings.useEverywhere(context,providerId,localId);applied=true
                    } catch(e: CancellationException){throw e}
                    catch(e: Exception){failure=e.message ?: e.toString()}
                    finally {applying=false} }
                }) { Text(if(applying)uiText(UiR.string.ui_saving_56a22) else uiText(UiR.string.ui_use_this_translator_across_hearth_456c5)) }
                Text(uiText(UiR.string.ui_applies_to_every_feature_restart_active_camera_reading_sessions_t_1fdea),style=MaterialTheme.typography.bodySmall)
                if(applied)Text(uiText(UiR.string.ui_translator_saved_for_all_features_59239),style=MaterialTheme.typography.bodySmall)
            }
            if (automaticLabel != null) TranslatorRow(automaticLabel, uiText(UiR.string.ui_keep_the_speech_engine_s_integrated_or_legacy_translation_route_31ea8), providerId.isBlank(), enabled) {
                onSelect("", localId); expanded = location != null
            }
            if(ByokPolicy.FEATURE_BYOK && location == null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!showCloud,onClick={cloudTab=false;editing=null},label={Text(uiText(UiR.string.ui_on_device_72a3f))})
                FilterChip(selected=showCloud,onClick={cloudTab=true;editing=null},label={Text(uiText(UiR.string.ui_cloud_server_ba8c1))})
            }
            if(!showCloud || !ByokPolicy.FEATURE_BYOK) {
            Text(uiText(UiR.string.ui_on_device_72a3f), style = MaterialTheme.typography.titleMedium)
            Text(uiText(UiR.string.ui_original_text_stays_on_your_phone_2fbea), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup()) {
                if (PlatformTranslation.available) TranslatorRow(TranslationOptions.label(TranslationOptions.ML_KIT),
                    uiText(UiR.string.ui_language_packs_required_manage_download_in_models_de735) + pair { a,b -> a in TranslationOptions.mlKitCodes && b in TranslationOptions.mlKitCodes },
                    providerId == "local" && localId == TranslationOptions.ML_KIT, enabled) {
                    onSelect("local", TranslationOptions.ML_KIT); expanded = location != null
                }
                MarianPackage.pairs.forEach { marian ->
                    val marianReady = registered.any { it.engineType == ModelEngineType.TRANSLATE &&
                        it.isValid && it.isDirectory && it.digest?.hex == marian.treeSha256 }
                    TranslatorRow(TranslationOptions.label(marian.id),
                        (if (marianReady) uiText(UiR.string.model_installed_verified) else uiText(UiR.string.model_marian_setup)) +
                            pair { a,b -> TranslationOptions.supports(marian.id,a,b) },
                        providerId == "local" && localId == marian.id, enabled,
                        actionLabel = if (marianReady) null else uiText(UiR.string.ui_set_up_a5041)) {
                        if (marianReady) { onSelect("local", marian.id); expanded = location != null }
                        else {
                            context.getSharedPreferences("translation-browser",0).edit()
                                .putString("model", marian.id).putBoolean("open",true).apply()
                            onModels()
                        }
                    }
                }
                MarianCascade.routes.forEach { route ->
                    val ready = TranslationOptions.installed(context, route.id)
                    TranslatorRow(TranslationOptions.label(route.id),
                        (if (ready) uiText(UiR.string.model_installed_verified) else uiText(UiR.string.model_marian_setup)) +
                            pair { a,b -> TranslationOptions.supports(route.id,a,b) },
                        providerId == "local" && localId == route.id, enabled,
                        actionLabel = if (ready) null else uiText(UiR.string.ui_set_up_a5041)) {
                        if (ready) { onSelect("local", route.id); expanded = location != null }
                        else {
                            context.getSharedPreferences("translation-browser",0).edit()
                                .putString("model", route.id).putBoolean("open",true).apply()
                            onModels()
                        }
                    }
                }
                TranslationCatalog.models.sortedWith(compareByDescending<com.sal7one.common_jni.translation.TranslationModelSpec> { providerId=="local" && it.id==localId }.thenByDescending { it.id in installed }).forEach { model ->
                    val ready = model.id in installed
                    TranslatorRow(model.label,
                        uiText(UiR.string.ui_1_s_2_s_mib_3_s_languages_01ef0, if (ready) uiText(UiR.string.model_installed) else uiText(UiR.string.model_download_import), model.bytes / 1_048_576, model.sourceLanguages.size) + pair(model::supports),
                        providerId == "local" && localId == model.id, enabled,
                        actionLabel = if (ready) null else uiText(UiR.string.ui_set_up_a5041)) {
                        if (ready) { onSelect("local", model.id); expanded = location != null } else {
                            context.getSharedPreferences("translation-browser",0).edit().putString("model",model.id).putBoolean("open",true).apply(); onModels()
                        }
                    }
                }
            }
            TextButton(onClick = { context.getSharedPreferences("translation-browser",0).edit().putBoolean("open",true).apply(); onModels() }, enabled = enabled) { Text(uiText(UiR.string.ui_model_downloads_imports_language_packs_1638e)) }
            }
            if (ByokPolicy.FEATURE_BYOK && showCloud) {
                Text(uiText(UiR.string.ui_cloud_self_hosted_2df1c), style = MaterialTheme.typography.titleMedium)
                Text(uiText(UiR.string.ui_recognized_or_typed_text_is_sent_to_the_chosen_server_your_speech_5f1ae), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    TextTranslationProvider.entries.forEach { provider ->
                        val capabilities = remember(revision, provider) { ConversationTranslationSettings.capabilities(context, provider) }
                        val ready = capabilities != null && (provider == TextTranslationProvider.LIBRETRANSLATE || ConversationTranslationSettings.hasKey(context, provider))
                        TranslatorRow(provider.label,
                            if (ready) uiText(UiR.string.ui_connection_saved_1_s_source_2_s_target_languages_f7ddd, capabilities!!.sourceLanguages.size, capabilities.targetLanguages.size) + pair(capabilities::supports)
                            else if (provider == TextTranslationProvider.LIBRETRANSLATE) uiText(UiR.string.ui_connect_your_server_key_depends_on_server_dd541) else uiText(UiR.string.ui_set_up_api_key_check_languages_db97d),
                            providerId == provider.id, enabled, actionLabel = if (ready) null else uiText(UiR.string.ui_set_up_a5041)) {
                            if (ready) { onSelect(provider.id, localId); expanded = location != null } else editing = provider
                        }
                        if (ready) TextButton(onClick = { editing = provider }, enabled = enabled) { Text(uiText(UiR.string.ui_manage_1_s_126c2, provider.label)) }
                    }
                }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            editing?.let { provider ->
                HorizontalDivider()
                TextButton(onClick = { editing = null }) { Text(uiText(UiR.string.ui_close_connection_setup_0f67b)) }
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
