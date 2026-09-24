package com.sal7one.transiber.shortcuts

import android.content.Context
import android.provider.Settings
import com.sal7one.common_jni.CommonJni
import com.sal7one.common_jni.speech.SpeechRuntime
import com.sal7one.common_jni.translation.TranslationCatalog
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
    val camera = context.getSharedPreferences("camera-translate", 0)
    val readingModel = OcrPreferences(context).read().translationModel(config.localTranslationModelId)
    val profileId = camera.getString("profile", "latin")!!
    val source = camera.getString("source", "en")!!
    val target = camera.getString("target", "ar")!!
    val provider = if (ByokPolicy.FEATURE_BYOK) camera.getString("provider", "local")!! else "local"
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
        SetupProbe("Native runtime", SetupFix.MODELS) { CommonJni.init(context.applicationContext); "Libraries initialized" },
    )
    if (shortcut == OverlayShortcut.CAPTIONS) {
        probes += SetupProbe("Speech recognition", if (config.engine == CaptionEngineChoice.CLOUD) SetupFix.CAPTIONS else SetupFix.MODELS) {
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
                    val model = (if (config.modelId.isBlank()) candidates.firstOrNull() else candidates.firstOrNull { it.id == config.modelId })
                        ?: error("Selected ${type.displayName} model is missing. Import or select it in Models.")
                    check(model.isValid && File(model.path).exists()) { "Selected speech model is unavailable: ${model.name}" }
                    model.name
                }
            }
        }
        probes += SetupProbe("Caption languages & translation", SetupFix.CAPTIONS) {
            val spoken = CaptionLanguages.effectiveSource(config, cloudMode, captionLanguageModel(context, config))
            when (captionTranslationRoute(config, cloudMode)) {
                CaptionTranslationRoute.ORIGINAL -> "Original captions · ${config.source.label}"
                CaptionTranslationRoute.UNSUPPORTED -> error("The selected speech path cannot translate with this setup. Choose a text translator or switch to original captions.")
                CaptionTranslationRoute.TEXT_TRANSLATOR -> {
                    if (spoken == "auto" && (config.engine in setOf(CaptionEngineChoice.WHISPER, CaptionEngineChoice.VOSK) ||
                        config.engine == CaptionEngineChoice.CLOUD && cloudMode in setOf(CloudConfigStore.SttMode.BATCH, CloudConfigStore.SttMode.STREAMING_ELEVENLABS))) {
                        error("This speech adapter needs a known spoken language for text translation. Set its supported spoken language or choose a speech engine that reports it.")
                    }
                    checkTranslator(context, config.localTranslationModelId, config.textTranslationProviderId.ifBlank { "local" }, spoken, config.target.languageTag)
                }
                CaptionTranslationRoute.ENGLISH_PIVOT, CaptionTranslationRoute.ENGLISH_TEXT -> {
                    val pair = checkNotNull(MarianPackage.find(TranslationOptions.MARIAN_EN_AR))
                    check(MarianPackage.installed(context, pair) != null) {
                        "Download the verified English → Arabic translation model, or choose a different text translator."
                    }
                    "English → Arabic translation model registered"
                }
                else -> {
                    check(config.target.languageTag in CaptionLanguages.target(config, cloudMode).codes) { "Unsupported translation target: ${config.target.languageTag}" }
                    "Speech engine translation → ${config.target.label}"
                }
            }
        }
    } else {
        probes += SetupProbe("OCR model & source language", SetupFix.CAMERA) {
            val profile = OcrCatalog.profile(profileId)
            check(source in profile.languages) { "${profile.label} does not support source language $source." }
            val missing = OcrModels(File(context.filesDir, "ocr-models")).missing(profile)
            check(missing.isEmpty()) { "Download or import ${profile.label}: ${missing.joinToString { it.filename }}" }
            "${profile.label} · $source"
        }
        probes += SetupProbe("Text translation", SetupFix.TRANSLATION) {
            checkTranslator(context, readingModel, provider, source, target)
        }
    }
    val signature = listOf(config.toString(), cloudMode.name, profileId, source, target, provider, readingModel).joinToString("\n")
    OverlayPreflight(config, signature, checkOverlaySetup(probes))
}

private suspend fun checkTranslator(context: Context, localId: String, providerId: String, source: String, target: String): String {
    val knownSource = source !in setOf("auto", "model", "und", "mul", "")
    if (source == target) return "$source → $target · original text (same language)"
    if (providerId != "local") {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." }
        val provider = ConversationTranslationSettings.provider(providerId) ?: error("Unknown translation provider: $providerId")
        val connection = ConversationTranslationSettings.connection(context, provider)
        val languages = ConversationTranslationSettings.capabilities(context, provider) ?: error("Check ${provider.label} languages in Translation settings first.")
        check(target in languages.targetLanguages && (!knownSource || languages.supports(source, target))) { "${provider.label} does not support $source → $target." }
        // Validate the real request contract without sending text or testing billing.
        CloudTranslationProtocol.translateRequest(connection, "Setup check", if (knownSource) source else languages.sourceLanguages.first { languages.supports(it, target) }, target)
        return "${provider.label} · saved connection and supported languages"
    }
    if (localId == TranslationOptions.ML_KIT) {
        check(PlatformTranslation.available) { "ML Kit is unavailable in this build. Select an imported local translator." }
        check(target in TranslationOptions.mlKitCodes && (!knownSource || source in TranslationOptions.mlKitCodes)) { "ML Kit does not support $source → $target." }
        val required = if (knownSource) setOf(source, target) else setOf(target)
        val missing = required - PlatformTranslation.installed()
        check(missing.isEmpty()) { "Download ML Kit language packs in Models: ${missing.joinToString()}" }
        return "ML Kit packs installed${if (!knownSource) " · source pack must match detected speech" else ""}"
    }
    check(localId.isNotBlank()) { "Choose a local translator in Live captions → Speech & translation." }
    MarianPackage.find(localId)?.let { pair ->
        check(target == pair.target && (!knownSource || source == pair.source)) {
            "Marian / OPUS-MT ${pair.source} → ${pair.target} does not support $source → $target."
        }
        check(MarianPackage.installed(context, pair) != null) {
            "Download and install Marian ${pair.source} → ${pair.target} in Models → Translation."
        }
        return "Marian / OPUS-MT · $source → $target · four files verified"
    }
    com.sal7one.transiber.translation.MarianCascade.find(localId)?.let { route ->
        check(target == route.target && (!knownSource || source == route.source)) {
            "Marian via English ${route.source} → ${route.target} does not support $source → $target."
        }
        check(com.sal7one.transiber.translation.MarianCascade.installed(context, route)) {
            "Install both Marian ${route.first.source} → English and English → ${route.target} in Models → Translation."
        }
        return "Marian / OPUS-MT · $source → English → $target · both models verified"
    }
    val model = TranslationCatalog.find(localId)
    check(target in model.targetLanguages && (!knownSource || model.supports(source, target))) { "${model.label} does not support $source → $target." }
    check(LocalTranslationModels(File(context.filesDir, "translation-models")).installed().any { it.id == localId }) { "Download or import ${model.label} in Models → Translation." }
    return "${model.label} · $source → $target"
}
