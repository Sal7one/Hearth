package com.sal7one.transiber.setup

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.caption.captionEngine
import com.sal7one.transiber.caption.speechBackend
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.models.SpeechArtifactKind

internal object PortableSetupCatalog {
    fun validateSpeech(stage: PortableSpeechStage) {
        val ref = stage.model
        if (ref.family == "cloud") {
            require(ref.catalogId != null && ref.profile == null) { "Cloud speech needs a local connection reference." }
            return
        }
        val artifact = ref.catalogId?.let { checkNotNull(SpeechArtifactCatalog.find(it)) { "Unknown speech catalog reference: $it" } }
        val engine = artifact?.engine ?: SpeechArtifactCatalog.all.firstOrNull { it.family == ref.family }?.engine
            ?: error("Unknown speech model family: ${ref.family}")
        if (artifact != null) {
            require(artifact.family == ref.family) { "Speech family does not match the catalog reference." }
            require(ref.profile == null || artifact.publisher?.profile?.id == ref.profile) { "Speech profile does not match the catalog reference." }
            require(stage.sourceLanguage == "auto" || stage.sourceLanguage in artifact.languages) { "The speech model does not support the spoken language." }
        } else if (engine.speechBackend != null) {
            require(ref.digestKind == "tree" && ref.profile != null) { "Custom speech packages need a profile and tree SHA-256." }
            val profile = SpeechProfile.fromId(checkNotNull(ref.profile))
            require(profile.captionEngine == engine) { "Custom speech profile belongs to a different engine." }
            require(stage.sourceLanguage == "auto" || stage.sourceLanguage in profile.capabilities.sourceLanguages) { "Custom speech profile does not support the spoken language." }
        } else {
            require(ref.profile == null) { "Legacy speech files do not have package profiles." }
            require(ref.digestKind == if (engine == CaptionEngineChoice.WHISPER) "file" else "tree") { "Speech digest type does not match the model family." }
        }
    }
}
