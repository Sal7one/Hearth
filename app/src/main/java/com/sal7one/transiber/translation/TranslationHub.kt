package com.sal7one.transiber.translation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TranslationHub(onModels: () -> Unit, initialLocation: SettingsLocation = SettingsLocation.LOCAL, entryRevision: Int = 0) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val config by remember {CaptionConfigStore.config(context)}.collectAsState(initial=CaptionOverlayConfig())
    val revision by ConversationTranslationSettings.revision.collectAsState()
    val provider=remember(revision){ConversationTranslationSettings.selected(context)}
    var error by remember {mutableStateOf<String?>(null)}
    SettingsTabs(initialLocation, entryRevision) { location ->
        SettingsHeading(if (location == SettingsLocation.LOCAL) "Local translation" else "Cloud translation",
            "Choose a translator here. Browsing tabs does not change the active translator.")
        Text("Active: ${ConversationTranslationSettings.label(context, config.localTranslationModelId)}", style = MaterialTheme.typography.labelLarge)
        TranslatorChooser(provider,config.localTranslationModelId,"This choice is shared by Conversation, Face to face and typed text. Use across Hearth also updates captions and camera.",
            onModels=onModels,location=location,onSelect={id,model->scope.launch {
                try { CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};ConversationTranslationSettings.select(context,id) }
                catch(e: CancellationException){throw e}
                catch(e: Exception){error=e.message ?: e.toString()}
            }})
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
