package com.sal7one.transiber.setup

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.models.SpeechArtifact
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.translation.MarianPackage

internal enum class LocalBundleKind { COMPACT_CAPTIONS, FAST_TRANSLATION, BROAD_TRANSLATION }

/** Bundles reference existing pinned catalogs; they never create another downloader. */
internal data class LocalBundlePreset(
    val kind: LocalBundleKind,
    val speech: SpeechArtifact,
    val translatorId: String?,
    val downloadBytes: Long,
    val setup: PortableSetup,
)

internal object LocalBundlePresets {
    const val BROAD_TRANSLATOR = "index-translate-2b-q4"
    fun create(kind: LocalBundleKind, source: String, target: String, translationOnly: Boolean = false): LocalBundlePreset? {
        if (translationOnly && kind == LocalBundleKind.COMPACT_CAPTIONS) return null
        val artifact = checkNotNull(SpeechArtifactCatalog.find(when (kind) {
            LocalBundleKind.COMPACT_CAPTIONS -> "whisper-tiny-q5_1"
            LocalBundleKind.FAST_TRANSLATION -> "whisper-base-q5_1"
            LocalBundleKind.BROAD_TRANSLATION -> SpeechProfile.NEMOTRON_3_5_ASR_0_6B.id
        }))
        if (!translationOnly && source !in artifact.languages) return null
        val pair = if (kind == LocalBundleKind.FAST_TRANSLATION)
            MarianPackage.pairs.firstOrNull { it.source == source && it.target == target } ?: return null else null
        val broad = if (kind == LocalBundleKind.BROAD_TRANSLATION) TranslationCatalog.find(BROAD_TRANSLATOR) else null
        if (kind != LocalBundleKind.COMPACT_CAPTIONS && (source == target ||
                (broad != null && (source !in broad.sourceLanguages || target !in broad.targetLanguages)))) return null
        val translator = pair?.id ?: broad?.id
        val setup = PortableSetup(name = kind.name.lowercase().replace('_', ' '),
            speech = if (translationOnly) null else PortableSpeechStage(PortableModelRef(artifact.family, catalogId = artifact.id), source),
            translation = translator?.let { PortableTranslationStage(it, source, target) })
        return LocalBundlePreset(kind, artifact, translator,
            (if (translationOnly) 0 else artifact.bytes) + (pair?.downloadBytes ?: broad?.bytes ?: 0), setup)
    }
}
