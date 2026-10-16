package com.sal7one.transiber.setup

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.models.*
import com.sal7one.transiber.shortcuts.checkSpeechAssetPresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class TvSetupStatus(val loading: Boolean = true, val configured: Boolean = false, val error: String? = null)

/** Saved configuration, not a promise about provider quota, capture permission or model runtime. */
internal suspend fun readTvSetupStatus(context: Context, config: CaptionOverlayConfig): TvSetupStatus = withContext(Dispatchers.IO) {
    try {
        val configured = if (ByokPolicy.FEATURE_BYOK) {
            val official = config.engine == CaptionEngineChoice.CLOUD &&
                CloudConfigStore.sttMode(context) == CloudConfigStore.SttMode.STREAMING_OPENAI &&
                EasySetupPreset.canReuseKey(CloudConfigStore.Provider.OPENAI, CloudConfigStore.Provider.OPENAI.baseUrl,
                    CloudConfigStore.provider(context), CloudConfigStore.baseUrl(context))
            official && synchronized(ApiKeyStore) {
                val present = ApiKeyStore.hasOpenAiKey(context)
                ApiKeyStore.lastFailure?.let { error(it) }
                present
            }
        } else when {
            config.engine == CaptionEngineChoice.CLOUD -> false
            config.engine.speechBackend != null -> {
                val model = LocalSpeechModels(File(context.filesDir, "speech-models")).select(config.engine, config.modelId)
                checkSpeechAssetPresence(model.root)
                true
            }
            else -> {
                val type = if (config.engine == CaptionEngineChoice.VOSK) ModelEngineType.VOSK else ModelEngineType.WHISPER
                ModelRegistry.getInstance(context).getModelsForEngine(type).any {
                    it.isValid && File(it.path).exists() && (config.modelId.isBlank() || config.modelId == it.id)
                }
            }
        }
        TvSetupStatus(loading = false, configured = configured)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { TvSetupStatus(loading = false, error = e.message ?: e.toString()) }
}

/** Refresh after phone pairing, full-interface edits and returning from Android settings. */
@Composable
internal fun rememberTvSetupStatus(config: CaptionOverlayConfig?): TvSetupStatus {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(TvSetupStatus()) }
    DisposableEffect(context, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val stores = if (ByokPolicy.FEATURE_BYOK) listOf("byok_keys", "byok_cloud_config").map { context.getSharedPreferences(it, 0) } else emptyList()
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose {
            stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(config, revision) { if (config != null) status = readTvSetupStatus(context, config) }
    return status
}
