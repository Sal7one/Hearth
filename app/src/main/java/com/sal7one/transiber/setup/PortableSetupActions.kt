package com.sal7one.transiber.setup

import android.content.Context
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.models.*
import com.sal7one.transiber.reading.ReadingOverlayService
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.CancellationException
import com.sal7one.common_jni.language.LanguageCatalog

internal data class PortableSetupPreview(
    val issues: List<String>,
    val speechModelId: String? = null,
    val missingSpeechCatalogId: String? = null,
    val missingTranslationIds: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val speechEngine: CaptionEngineChoice? = null,
) {
    val ready get() = issues.isEmpty() && missingSpeechCatalogId == null && missingTranslationIds.isEmpty()
}

/** Reuses the production catalog/installers. JSON cannot introduce a download host or executable stage. */
internal class PortableSetupActions(context: Context) {
    private val context = context.applicationContext
    private val registry get() = ModelRegistry.getInstance(context)
    private val speechStore get() = LocalSpeechModels(File(context.filesDir, "speech-models"))
    private val verifier = SetupModelVerifier()
    private val catalogInstallations = SpeechCatalogInstallations(File(context.filesDir, "speech-models"), verifier)

    suspend fun preview(setup: PortableSetup, fullIntegrity: Boolean = false): PortableSetupPreview = withContext(Dispatchers.IO) {
        val issues = mutableListOf<String>(); val notes = mutableListOf<String>()
        if (setup.speech != null && setup.translation?.enabled != true) notes += "Captions only: the text translation stage will be disabled."
        if (setup.speech == null) notes += "Speech recognition settings remain unchanged; no STT model is required for this setup."
        if (setup.voice == null) notes += "Voice settings remain unchanged."
        var installedSpeech: String? = null; var engine: CaptionEngineChoice? = null; var missingSpeech: String? = null
        setup.speech?.let { stage ->
            try {
                PortableSetupCatalog.validateSpeech(stage)
                if (stage.model.family == "cloud") {
                    check(ByokPolicy.FEATURE_BYOK) { "Cloud speech is unavailable in the offline build." }
                    check(stage.model.catalogId == CloudConfigStore.provider(context).name.lowercase()) {
                        "Select the referenced speech connection on this device in Advanced settings."
                    }
                    check(!cloudSpeechKeyRequired(CloudConfigStore.sttMode(context), CloudConfigStore.provider(context)) || ApiKeyStore.hasOpenAiKey(context)) {
                        "Configure the speech connection on this device; credentials are excluded from setup files."
                    }
                    engine = CaptionEngineChoice.CLOUD; installedSpeech = ""
                    notes += "Cloud speech uses this device’s saved connection. Server, model and credentials are excluded from the file."
                } else {
                    val artifact = stage.model.catalogId?.let { checkNotNull(SpeechArtifactCatalog.find(it)) { "Unknown speech catalog reference: $it" } }
                    if (artifact != null) {
                        check(artifact.family == stage.model.family) { "Speech family does not match the catalog reference." }
                        check(stage.sourceLanguage == "auto" || stage.sourceLanguage in artifact.languages) { "The speech model does not support ${stage.sourceLanguage}." }
                        engine = artifact.engine
                        installedSpeech = resolveCatalogSpeech(artifact, fullIntegrity)
                        if (installedSpeech == null) missingSpeech = artifact.id
                    } else {
                        engine = SpeechArtifactCatalog.all.firstOrNull { it.family == stage.model.family }?.engine
                            ?: error("Unknown speech model family: ${stage.model.family}")
                        installedSpeech = resolveCustomSpeech(stage.model, checkNotNull(engine), fullIntegrity)
                        if (installedSpeech == null) issues += "Import or locate the ${stage.model.family} model with SHA-256 ${stage.model.sha256}."
                        else if (engine == CaptionEngineChoice.WHISPER || engine == CaptionEngineChoice.VOSK) {
                            val model = checkNotNull(registry.getModel(checkNotNull(installedSpeech)))
                            val supported = if (engine == CaptionEngineChoice.WHISPER) when (File(model.path).inputStream().use(::whisperVocabulary)) {
                                51864 -> setOf("en")
                                51865 -> LanguageCatalog.whisperCodes - "yue" + "auto"
                                51866 -> LanguageCatalog.whisperCodes + "auto"
                                else -> setOf("auto")
                            } else VoskDownloads.findByTreeSha256(model.digest?.hex)?.let { setOf(it.language, "auto") } ?: setOf("model")
                            check(stage.sourceLanguage in supported) { "The imported speech model does not support the selected source language." }
                        }
                        stage.model.profile?.let { id ->
                            val profile = SpeechProfile.fromId(id)
                            check(profile.captionEngine == engine) { "Custom speech profile belongs to a different engine." }
                            check(stage.sourceLanguage == "auto" || stage.sourceLanguage in profile.capabilities.sourceLanguages) { "Custom speech profile does not support the spoken language." }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { issues += e.message ?: e.toString() }
        }
        val missingTranslations = mutableListOf<String>()
        setup.translation?.takeIf { it.enabled }?.let { stage ->
            try {
                if (stage.modelId.startsWith("provider-")) {
                    check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." }
                    val provider = checkNotNull(ConversationTranslationSettings.provider(stage.modelId.removePrefix("provider-"))) { "Unknown translation connection." }
                    val languages = checkNotNull(ConversationTranslationSettings.capabilities(context, provider)) { "Check this device’s translation connection in Advanced settings first." }
                    check(languages.supports(stage.sourceLanguage, stage.targetLanguage)) { "The saved translation connection does not support this language pair." }
                    // Build only: validate existing endpoint, key/region and language bindings without sending text.
                    CloudTranslationProtocol.translateRequest(ConversationTranslationSettings.connection(context, provider),
                        "setup validation", stage.sourceLanguage, stage.targetLanguage, languages)
                    notes += "Cloud translation uses this device’s saved connection. Server, model and credentials are excluded from the file."
                } else {
                    check(TranslationOptions.supports(stage.modelId, stage.sourceLanguage, stage.targetLanguage)) { "The selected translator does not support ${stage.sourceLanguage} → ${stage.targetLanguage}." }
                    val pairs = MarianCascade.find(stage.modelId)?.let { listOf(it.first, it.second) }
                        ?: MarianPackage.find(stage.modelId)?.let(::listOf)
                    if (pairs != null) pairs.forEach { pair ->
                        val installed = MarianPackage.installed(context, pair)
                        if (installed == null) missingTranslations += pair.id
                        else verifier.inspect(File(installed.path), pair.treeSha256, fullIntegrity)
                    } else if (stage.modelId == TranslationOptions.ML_KIT) {
                        check(PlatformTranslation.available) { "Platform language packs are unavailable in this build." }
                        // Pack installation/readiness is owned by its existing platform setup.
                        val installed = PlatformTranslation.installed()
                        check(stage.sourceLanguage in installed && stage.targetLanguage in installed) { "Install the selected platform language packs in Translation settings." }
                    } else {
                        val spec = TranslationCatalog.find(stage.modelId)
                        val file = LocalTranslationModels(File(context.filesDir, "translation-models")).file(spec)
                        if (!file.isFile) missingTranslations += spec.id else verifier.inspect(file, spec.sha256, fullIntegrity)
                    }
                }
                setup.speech?.let { speech ->
                    check(speech.sourceLanguage == stage.sourceLanguage) { "Speech and translation source languages must match. Choose the spoken language explicitly." }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { issues += e.message ?: e.toString() }
        }
        setup.voice?.takeIf { it.enabled }?.let { voice ->
            try {
                when (voice.choice) {
                    CaptionSpeakerChoice.SYSTEM -> notes += "Android voice availability is checked by the device when speech starts."
                    CaptionSpeakerChoice.NATIVE -> {
                        val id = voice.voiceId ?: VoiceSettings.choice(context).voice
                        check(id in VoiceCatalog.voices) { "Select a supported Supertonic voice." }
                        check(VoiceModels(File(context.filesDir, "voice-models")).ready(id)) { "Download or import Supertonic 3 and voice $id in Voices first." }
                    }
                    CaptionSpeakerChoice.CLOUD -> {
                        check(ByokPolicy.FEATURE_BYOK) { "Cloud voice is unavailable in the offline build." }
                        check(UserPreferences.store(context, "cloud_voice_v1").contains("provider")) { "Configure this device’s cloud voice in Voices first." }
                        check(CloudVoiceStore.hasKey(context, CloudVoiceStore.config(context).validated())) { "Configure the cloud voice on this device; its connection and credentials are excluded from setup files." }
                        notes += "Cloud voice uses this device’s saved connection. Voice server and credentials are excluded from the file."
                    }
                    CaptionSpeakerChoice.SHARED, CaptionSpeakerChoice.CUSTOM -> {
                        val backend = if (voice.choice == CaptionSpeakerChoice.CUSTOM) VoiceSettings.customBackend(context)
                            ?: error("Select a custom voice on this device first.") else VoiceSettings.choice(context).backend
                        if (backend == "supertonic") check(VoiceModels(File(context.filesDir, "voice-models")).ready(VoiceSettings.choice(context).voice)) { "Install the shared on-device voice in Voices first." }
                        if (backend == "remote") { check(ByokPolicy.FEATURE_BYOK) { "Remote voice is unavailable in the offline build." }; VoiceSettings.remote(context) }
                        notes += "Shared/custom voice settings stay on this device; their connection and credentials are excluded from the file."
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { issues += e.message ?: e.toString() }
        }
        PortableSetupPreview(issues.distinct(), installedSpeech, missingSpeech, missingTranslations.distinct(), notes.distinct(), engine)
    }

    private fun resolveCatalogSpeech(artifact: SpeechArtifact, fullIntegrity: Boolean): String? = when (artifact.kind) {
        SpeechArtifactKind.WHISPER, SpeechArtifactKind.VOSK -> {
            val hash = VoskDownloads.find(artifact.id)?.treeSha256 ?: artifact.sha256
            registry.getModelsForEngine(if (artifact.kind == SpeechArtifactKind.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK)
                .firstOrNull { it.digest?.hex == hash }?.also { model ->
                    verifier.inspect(File(model.path), hash, fullIntegrity)
                }?.id
        }
        SpeechArtifactKind.PUBLISHER -> {
            val snapshot = catalogInstallations.scan(listOf(artifact), emptyList(), speechStore.inspect().models,
                fullIntegrity = fullIntegrity)
            snapshot.publisher[artifact.id]?.id.also {
                if (it == null && snapshot.issues.isNotEmpty()) error(snapshot.issues.joinToString("\n"))
            }
        }
    }
    private fun resolveCustomSpeech(ref: PortableModelRef, engine: CaptionEngineChoice, fullIntegrity: Boolean): String? {
        if (engine.speechBackend == null) return registry.getModelsForEngine(if (engine == CaptionEngineChoice.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK)
            .firstOrNull { it.digest?.hex == ref.sha256 && it.digest?.kind?.name?.lowercase() == ref.digestKind }?.also {
                verifier.inspect(File(it.path), ref.sha256, fullIntegrity)
            }?.id
        check(ref.digestKind == "tree" && ref.profile != null) { "Custom speech packages need a profile and tree SHA-256." }
        val issues = mutableListOf<String>()
        val matched = speechStore.inspect().models.filter { it.profile.id == ref.profile }.firstOrNull { model ->
            try {
                verifier.speech(model.root, fullIntegrity)
                verifier.inspect(model.root, force = fullIntegrity).digest.hex == ref.sha256
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { issues += "${model.id}: ${e.message ?: e.toString()}"; false }
        }
        if (matched == null && issues.isNotEmpty()) error(issues.joinToString("\n"))
        return matched?.id
    }
    suspend fun downloadMissing(setup: PortableSetup) = withContext(Dispatchers.IO) {
        check(ByokPolicy.FEATURE_BYOK) { "Import models in the offline build." }; idle()
        val state = preview(setup); val downloads = FileDownloads(context)
        state.missingSpeechCatalogId?.let { id ->
            val artifact = checkNotNull(SpeechArtifactCatalog.find(id))
            downloads.enqueue(DownloadSpec.parse(artifact.url, artifact.fileName), true, artifact.id)
        }
        state.missingTranslationIds.forEach { id ->
            val pair = MarianPackage.find(id)
            if (pair != null) MarianPackage.enqueue(context, downloads, pair)
            else TranslationCatalog.find(id).let { downloads.enqueue(DownloadSpec.parse(it.url, it.fileName), true, it.id) }
        }
    }
    private fun idle() {
        check(!CaptionCaptureService.running.value && !ReadingOverlayService.running.value && LocalWorkGate.owner.value == null) { "Stop the current session before applying a setup." }
    }
    suspend fun apply(setup: PortableSetup) {
        idle(); val state = preview(setup, fullIntegrity = true)
        check(state.ready) { (state.issues + listOfNotNull(state.missingSpeechCatalogId) + state.missingTranslationIds).joinToString("\n").ifBlank { "Install the required models first." } }
        idle()
        setup.voice?.takeIf { it.choice == CaptionSpeakerChoice.NATIVE && it.voiceId != null }?.let {
            VoiceSettings.save(context, VoiceSettings.choice(context).copy(backend = "supertonic", voice = checkNotNull(it.voiceId)))
        }
        CaptionConfigStore.update(context) { previous -> PortableSetupPlan.apply(previous, setup, state) }
        setup.translation?.takeIf { it.enabled }?.let { stage ->
            val localId = if (stage.modelId.startsWith("provider-")) CaptionConfigStore.config(context).first().localTranslationModelId else stage.modelId
            check(UserPreferences.store(context, "typed-translation").edit()
                .putString("source", stage.sourceLanguage).putString("target", stage.targetLanguage).commit()) { "Could not save typed translation languages" }
            com.sal7one.transiber.ocr.OcrPreferences(context).update { it.copy(source = stage.sourceLanguage, target = stage.targetLanguage) }
            ConversationTranslationSettings.useEverywhere(context, if (stage.modelId.startsWith("provider-")) stage.modelId.removePrefix("provider-") else "local", localId)
        }
    }
    suspend fun exportCurrent(name: String = "My Hearth setup", includeSpeech: Boolean = true,
        includeTranslation: Boolean = true, includeVoice: Boolean = true): PortableSetup = withContext(Dispatchers.IO) {
        val config = CaptionConfigStore.config(context).first()
        val speech = if (!includeSpeech) null else if (config.engine == CaptionEngineChoice.CLOUD) PortableSpeechStage(
            PortableModelRef("cloud", CloudConfigStore.provider(context).name.lowercase()), config.streamLanguage)
        else if (config.engine.speechBackend != null) {
            val model = speechStore.select(config.engine, config.modelId)
            SpeechModelPackage.verify(model.root)
            val family = SpeechArtifactCatalog.all.firstOrNull { it.engine == config.engine }?.family ?: error("Unsupported speech family")
            val known = SpeechArtifactCatalog.forEngine(config.engine).firstOrNull { artifact ->
                artifact.publisher?.profile == model.profile && resolveCatalogSpeech(artifact, fullIntegrity = true) == model.id
            }
            val ref = if (known != null) PortableModelRef(family, catalogId = known.id)
                else ModelIntegrity.inspect(model.root).digest.let { PortableModelRef(family, profile = model.profile.id, sha256 = it.hex, digestKind = "tree") }
            PortableSpeechStage(ref, config.streamLanguage)
        } else {
            val type = if (config.engine == CaptionEngineChoice.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK
            val model = registry.getModel(config.modelId) ?: registry.getModelsForEngine(type).firstOrNull { config.modelId.isBlank() }
                ?: error("Select an installed speech model before exporting.")
            val file = File(checkNotNull(registry.getProvider(model.id)).getModelPath())
            val digest = ModelIntegrity.inspect(file).digest
            val family = if (config.engine == CaptionEngineChoice.WHISPER) "whisper" else "vosk"
            val artifact = SpeechArtifactCatalog.all.firstOrNull { it.family == family &&
                (VoskDownloads.find(it.id)?.treeSha256 ?: it.sha256) == digest.hex }
            PortableSpeechStage(if (artifact != null) PortableModelRef(family, artifact.id)
                else PortableModelRef(family, sha256 = digest.hex, digestKind = digest.kind.name.lowercase()), config.streamLanguage)
        }
        val translator = config.textTranslationProviderId.takeIf { it.isNotBlank() && it != "local" }?.let { "provider-$it" }
            ?: config.localTranslationModelId.takeIf { it.isNotBlank() }
        PortableSetup(name, speech, translator?.takeIf { includeTranslation }?.let { PortableTranslationStage(it, config.streamLanguage, config.target.languageTag,
            config.mode == CaptionMode.TRANSLATE && config.localTranslationEnabled) },
            if (includeVoice) PortableVoiceStage(config.speakCaptions, config.speakerChoice, config.speakerGender, config.speakerVolume,
                VoiceSettings.choice(context).voice.takeIf { config.speakerChoice == CaptionSpeakerChoice.NATIVE }) else null)
    }
}
