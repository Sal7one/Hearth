package com.sal7one.transiber.shortcuts

import android.content.Context
import android.provider.Settings
import com.sal7one.common_jni.CommonJni
import com.sal7one.common_jni.speech.SpeechRuntime
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.models.*
import com.sal7one.transiber.ocr.*
import com.sal7one.transiber.reading.ReadingOverlayService
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

internal data class OverlayPreflight(val config: CaptionOverlayConfig, val signature: String, val results: List<SetupResult>)

/** No downloads, remote requests or recognizer allocation. Runtime still verifies weights when opening. */
internal suspend fun overlayPreflight(context: Context, shortcut: OverlayShortcut): OverlayPreflight = withContext(Dispatchers.IO) {
    val config = CaptionConfigStore.config(context).first()
    val reading = OcrPreferences(context).read()
    val readingModel = reading.translationModel(config.localTranslationModelId)
    val ocrModels = OcrModels(File(context.filesDir, "ocr-models"))
    val profile by lazy { ocrModels.effectiveProfile(reading) }
    val source = reading.source
    val target = reading.target
    val provider = reading.providerId
    val cloudMode = CloudConfigStore.sttMode(context)
    val probes = mutableListOf(
        SetupProbe("Display over other apps", SetupFix.OVERLAY_PERMISSION) {
            check(Settings.canDrawOverlays(context)) { "Allow Hearth to display over other apps." }; "Allowed"
        },
        SetupProbe("Current session", SetupFix.SESSION) {
            check(!CaptionCaptureService.running.value && !ReadingOverlayService.running.value) { "Another overlay is running. Stop it before starting ${shortcut.label}." }
            check(LocalWorkGate.owner.value == null) { "${LocalWorkGate.owner.value} is still running or releasing its models. Stop it before starting ${shortcut.label}." }
            "No conflicting session"
        },
        SetupProbe("Native runtime", SetupFix.MODELS) {
            if (com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) {
                CommonJni.init(context.applicationContext); "Libraries initialized"
            } else "Cloud connections only on TV"
        },
    )
    if (shortcut == OverlayShortcut.CAPTIONS) {
        probes += SetupProbe("Speech recognition", if (config.engine == CaptionEngineChoice.CLOUD) SetupFix.CAPTIONS else SetupFix.MODELS) {
            if (config.effectiveEngine != CaptionEngineChoice.CLOUD)
                com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "speech recognition")
            when {
                config.engine == CaptionEngineChoice.CLOUD -> {
                    check(ByokPolicy.FEATURE_BYOK) { "Cloud speech is unavailable in the offline build." }
                    val secret = when (cloudMode) {
                        CloudConfigStore.SttMode.STREAMING_SONIOX -> ApiKeyStore.getSonioxKey(context)
                        CloudConfigStore.SttMode.STREAMING_ELEVENLABS -> ApiKeyStore.getElevenLabsKey(context)
                        CloudConfigStore.SttMode.STREAMING_DEEPGRAM -> ApiKeyStore.getDeepgramKey(context)
                        CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI -> ApiKeyStore.getAssemblyAiKey(context)
                        else -> ApiKeyStore.getOpenAiKey(context)
                    }
                    ApiKeyStore.lastFailure?.let { error(it) }
                    check(!com.sal7one.transiber.byok.cloudSpeechKeyRequired(cloudMode,CloudConfigStore.provider(context)) || secret.isNotBlank()) { "${cloudMode.label}: API key missing. Add it in Cloud speech settings." }
                    if (cloudMode == CloudConfigStore.SttMode.BATCH) {
                        val url = java.net.URI(CloudConfigStore.baseUrl(context))
                        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null) { "Cloud speech requires a valid HTTPS base URL without embedded credentials." }
                        require(CloudConfigStore.sttModel(context).isNotBlank()) { "Cloud speech model is missing." }
                    }
                    "${cloudMode.label} · connection configured (quota/connectivity checked when connecting)"
                }
                config.engine.speechBackend != null -> {
                    val model = LocalSpeechModels(File(context.filesDir, "speech-models")).select(config.engine, config.modelId)
                    checkSpeechAssetPresence(model.root)
                    val available = SpeechRuntime().availability(model.profile.backend)
                    check(available.available) { available.error ?: "${model.profile.label} runtime unavailable" }
                    // Full asset checksums remain at SpeechRuntime.open; don't hash gigabytes on every tile tap.
                    "${model.profile.label} · package registered"
                }
                else -> {
                    val type = if (config.engine == CaptionEngineChoice.VOSK) ModelEngineType.VOSK else ModelEngineType.WHISPER
                    val registry = ModelRegistry.getInstance(context)
                    val candidates = registry.getModelsForEngine(type)
                    val model = selectCaptionSpeechModel(candidates, config.modelId)
                        ?: error("Selected ${type.displayName} model is missing. Import or select it in Models.")
                    check(model.isValid && File(model.path).exists()) { "Selected speech model is unavailable: ${model.name}" }
                    model.name
                }
            }
        }
        probes += SetupProbe("Caption languages & translation", SetupFix.CAPTIONS) {
            checkCaptionTranslationSetup(context, config, cloudMode)
        }
    } else {
        probes += SetupProbe("OCR model & source language", SetupFix.CAMERA) {
            com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "OCR")
            check(source in profile.languages) { "${profile.label} does not support source language $source." }
            val missing = ocrModels.missing(profile)
            check(missing.isEmpty()) { "Download or import ${profile.label}: ${missing.joinToString { it.filename }}" }
            "${profile.label} · $source"
        }
        probes += SetupProbe("Text translation", SetupFix.TRANSLATION) {
            checkTranslationSetup(context, readingModel, provider, source, target)
        }
    }
    val readerId = if (shortcut == OverlayShortcut.CAPTIONS) reading.profileId else runCatching { profile.id }.getOrDefault(reading.profileId)
    val signature = listOf(config.toString(), cloudMode.name, readerId, source, target, provider, readingModel).joinToString("\n")
    OverlayPreflight(config, signature, checkOverlaySetup(probes))
}
