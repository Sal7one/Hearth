package com.sal7one.transiber.caption

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.translation.ConversationTranslationSettings
import com.sal7one.transiber.translation.PlatformTranslation
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.translation.checkTranslationSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CaptionTranslationReadiness(
    val ready: Boolean = false,
    val checking: Boolean = false,
    val detail: String,
)

/** These callbacks do not supply a reliable detected source. A verified fixed model still counts as known. */
internal fun captionRequiresKnownTranslationSource(engine: CaptionEngineChoice, cloudMode: CloudConfigStore.SttMode): Boolean =
    engine in setOf(CaptionEngineChoice.WHISPER, CaptionEngineChoice.VOSK, CaptionEngineChoice.TRANSCRIBEPSY,
        CaptionEngineChoice.OMNILINGUAL, CaptionEngineChoice.VOXTRAL, CaptionEngineChoice.PHONON) ||
        engine == CaptionEngineChoice.CLOUD && cloudMode != CloudConfigStore.SttMode.STREAMING_SONIOX

/** Exactly the check used by shortcut/start preflight. No network, model inference or weight hashing. */
internal suspend fun checkCaptionTranslationSetup(
    context: Context,
    config: CaptionOverlayConfig,
    cloudMode: CloudConfigStore.SttMode,
    model: CaptionLanguageModel? = null,
): String = when (captionTranslationRoute(config, cloudMode)) {
    CaptionTranslationRoute.ORIGINAL -> "Original captions · ${config.source.label}"
    CaptionTranslationRoute.UNSUPPORTED -> error("The selected speech path cannot translate with this setup. Choose a text translator or switch to original captions.")
    CaptionTranslationRoute.TEXT_TRANSLATOR -> {
        val spoken = CaptionLanguages.effectiveSource(config, cloudMode, model ?: captionLanguageModel(context, config))
        if (spoken == "auto" && captionRequiresKnownTranslationSource(config.engine, cloudMode)) {
            if (config.engine == CaptionEngineChoice.CLOUD && cloudMode == CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI) {
                error("AssemblyAI's current speech adapter does not report the detected language or accept a spoken-language selection. Choose a speech engine that reports its language, or switch to original captions; changing the translation model will not fix this adapter limitation.")
            }
            error("This speech adapter needs a known spoken language for text translation. Set its supported spoken language or choose a speech engine that reports it.")
        }
        checkTranslationSetup(context, config.localTranslationModelId, config.textTranslationProviderId.ifBlank { "local" }, spoken, config.target.languageTag)
    }
    CaptionTranslationRoute.ENGLISH_PIVOT, CaptionTranslationRoute.ENGLISH_TEXT ->
        checkTranslationSetup(context, TranslationOptions.MARIAN_EN_AR, "local", "en", "ar")
    else -> {
        check(config.target.languageTag in CaptionLanguages.target(config, cloudMode, model = model ?: CaptionLanguageModel(config.modelId)).codes) {
            "Unsupported translation target: ${config.target.languageTag}"
        }
        "Speech engine translation → ${config.target.label}"
    }
}

internal suspend fun readCaptionTranslationReadiness(check: suspend () -> String): CaptionTranslationReadiness = try {
    CaptionTranslationReadiness(ready = true, detail = check())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    CaptionTranslationReadiness(detail = error.message ?: error.toString())
} catch (error: LinkageError) {
    CaptionTranslationReadiness(detail = "Translation runtime unavailable: ${error.message ?: error}")
}

@Composable
internal fun rememberCaptionSetupResumeRevision(): Int {
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** Returning from model setup, repairing the same ID, and changing a connection all invalidate readiness. */
@Composable
internal fun rememberCaptionTranslationReadiness(
    config: CaptionOverlayConfig,
    cloudMode: CloudConfigStore.SttMode,
): CaptionTranslationReadiness {
    val context = LocalContext.current
    val resumed = rememberCaptionSetupResumeRevision()
    val registered by ModelRegistry.getInstance(context).registeredModels.collectAsStateWithLifecycle()
    val bindings by ModelSourceBindings.checks.collectAsStateWithLifecycle()
    val connectionRevision by ConversationTranslationSettings.revision.collectAsStateWithLifecycle()
    val packRevision by PlatformTranslation.revision.collectAsStateWithLifecycle()
    // Overlay appearance and transient controls must not restart a filesystem check.
    val selection = listOf(config.engine, config.modelId, config.speechSelectionRevision, config.mode,
        config.localTranslationEnabled, config.localTranslationModelId, config.textTranslationProviderId,
        config.streamLanguage, config.target, cloudMode, CloudConfigStore.sttModel(context))
    val request = listOf(selection, registered, bindings, connectionRevision, packRevision, resumed)
    // A new selection is pending immediately; never expose the old model's ready state for one frame.
    var state by remember(request) {
        mutableStateOf(CaptionTranslationReadiness(checking = true, detail = "Checking translation setup…"))
    }
    LaunchedEffect(request) {
        state = withContext(Dispatchers.IO) {
            readCaptionTranslationReadiness { checkCaptionTranslationSetup(context, config, cloudMode) }
        }
    }
    return state
}
