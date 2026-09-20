package com.sal7one.transiber.translation

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TranslationHub(onModels: () -> Unit, initialLocation: SettingsLocation = SettingsLocation.LOCAL, entryRevision: Int = 0) {
    val uiText = rememberUiText()

    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val config by remember {CaptionConfigStore.config(context)}.collectAsState(initial=CaptionOverlayConfig())
    val revision by ConversationTranslationSettings.revision.collectAsState()
    val provider=remember(revision){ConversationTranslationSettings.selected(context)}
    var error by remember {mutableStateOf<String?>(null)}
    SettingsTabs(initialLocation, entryRevision) { location ->
        SettingsHeading(if (location == SettingsLocation.LOCAL) uiText(UiR.string.ui_local_translation_010cc) else uiText(UiR.string.ui_cloud_translation_e9d00),
            uiText(UiR.string.ui_choose_a_translator_here_browsing_tabs_does_not_change_the_active_3b0c1))
        Text(uiText(UiR.string.ui_active_1_s_830d0, ConversationTranslationSettings.label(context, config.localTranslationModelId)), style = MaterialTheme.typography.labelLarge)
        TranslatorChooser(provider,config.localTranslationModelId,uiText(UiR.string.ui_this_choice_is_shared_by_conversation_face_to_face_and_typed_text_f76d4),
            onModels=onModels,location=location,onSelect={id,model->scope.launch {
                try { CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};ConversationTranslationSettings.select(context,id) }
                catch(e: CancellationException){throw e}
                catch(e: Exception){error=e.message ?: e.toString()}
            }})
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
