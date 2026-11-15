package com.sal7one.transiber.setup

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.models.SpeechArtifact
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.translation.MarianPackage

internal enum class LocalBundleKind(val translates: Boolean) {
    SHORT_CAPTIONS(false), PHONON_CAPTIONS(false), STREAMING_CAPTIONS(false), COMPACT_CAPTIONS(false),
    FAST_TRANSLATION(true), QUICKMT_TRANSLATION(true), BROAD_TRANSLATION(true),
}

/** Bundles reference existing pinned catalogs; they never create another downloader. */
internal data class LocalBundlePreset(
    val kind: LocalBundleKind,
    val speech: SpeechArtifact?,
    val translatorId: String?,
    val downloadBytes: Long,
    val setup: PortableSetup,
)

internal object LocalBundlePresets {
    const val BROAD_TRANSLATOR = "index-translate-2b-q4"
    private val shortSpeech = listOf(
        SpeechProfile.MOONSHINE_TINY_EN, SpeechProfile.MOONSHINE_BASE_AR,
        SpeechProfile.MOONSHINE_BASE_ES, SpeechProfile.MOONSHINE_TINY_JA,
        SpeechProfile.MOONSHINE_TINY_KO, SpeechProfile.MOONSHINE_BASE_UK,
        SpeechProfile.MOONSHINE_BASE_VI, SpeechProfile.MOONSHINE_BASE_ZH,
    ).associateBy { checkNotNull(it.fixedSourceLanguage) }

    private val quickMtIds = setOf("ct2-quickmt-en-ar", "ct2-quickmt-ar-en", "ct2-quickmt-ja-en-v2")

    fun translationBytes(id: String): Long =
        MarianPackage.find(id)?.downloadBytes
            ?: Ct2Catalog.find(id)?.let { it.archive?.bytes ?: it.bytes }
            ?: TranslationCatalog.find(id).bytes

    fun create(
        kind: LocalBundleKind,
        source: String,
        target: String,
        translationOnly: Boolean = false,
        supportsCt2: Boolean = false,
    ): LocalBundlePreset? {
        if (translationOnly && !kind.translates || kind.translates && source == target) return null
        val artifact = if (translationOnly) null else {
            val short = shortSpeech[source]?.id
            val phonon = SpeechProfile.PHONON_2_5.takeIf { source in it.capabilities.sourceLanguages }?.id
            val speechId = when (kind) {
                LocalBundleKind.SHORT_CAPTIONS -> short
                LocalBundleKind.PHONON_CAPTIONS -> phonon
                LocalBundleKind.STREAMING_CAPTIONS, LocalBundleKind.BROAD_TRANSLATION -> SpeechProfile.NEMOTRON_3_5_ASR_0_6B.id
                LocalBundleKind.COMPACT_CAPTIONS -> "whisper-tiny-q5_1"
                LocalBundleKind.FAST_TRANSLATION, LocalBundleKind.QUICKMT_TRANSLATION ->
                    short ?: phonon ?: "whisper-base-q5_1"
            } ?: return null
            SpeechArtifactCatalog.find(speechId)?.takeIf {
                it.profile?.acceptsSource(source) ?: (source == "auto" || source in it.languages)
            } ?: return null
        }
        val translatorId = when (kind) {
            LocalBundleKind.FAST_TRANSLATION -> MarianPackage.pairs
                .firstOrNull { it.source == source && it.target == target }?.id ?: return null
            LocalBundleKind.QUICKMT_TRANSLATION -> Ct2Catalog.models
                .firstOrNull { supportsCt2 && it.id in quickMtIds && it.downloadEnabled &&
                    it.supports(source, target) }?.id ?: return null
            LocalBundleKind.BROAD_TRANSLATION -> TranslationCatalog.find(BROAD_TRANSLATOR)
                .takeIf { source in it.sourceLanguages && target in it.targetLanguages }?.id ?: return null
            else -> null
        }
        val setup = PortableSetup(name = kind.name.lowercase().replace('_', ' '),
            speech = artifact?.let { PortableSpeechStage(PortableModelRef(it.family, catalogId = it.id), source) },
            translation = translatorId?.let { PortableTranslationStage(it, source, target) })
        return LocalBundlePreset(kind, artifact, translatorId,
            (artifact?.bytes ?: 0) + (translatorId?.let(::translationBytes) ?: 0), setup)
    }
}
