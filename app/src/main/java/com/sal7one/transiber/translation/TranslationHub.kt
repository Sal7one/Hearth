package com.sal7one.transiber.translation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TranslationHub(onModels: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val config by remember {CaptionConfigStore.config(context)}.collectAsState(initial=CaptionOverlayConfig())
    val revision by ConversationTranslationSettings.revision.collectAsState()
    val provider=remember(revision){ConversationTranslationSettings.selected(context)}
    var error by remember {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Translation",style=MaterialTheme.typography.titleLarge)
        Text("Choose an engine, install models or connect a service. Open the chooser to apply your selection across Hearth.")
        TranslatorChooser(provider,config.localTranslationModelId,"This choice is shared by Conversation, Face to face and typed text. Use across Hearth also updates captions and camera.",
            onModels=onModels,onSelect={id,model->scope.launch {
                try { CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};ConversationTranslationSettings.select(context,id) }
                catch(e: CancellationException){throw e}
                catch(e: Exception){error=e.message ?: e.toString()}
            }})
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        HorizontalDivider()
        ConversationTranslationSetup(onModels,allowSelection=false)
    }
}
