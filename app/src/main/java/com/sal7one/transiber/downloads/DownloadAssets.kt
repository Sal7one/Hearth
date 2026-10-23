package com.sal7one.transiber.downloads

import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.models.SpeechDownloads
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.voice.VoiceCatalog
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.Ct2Package

/** Identity for a transport artifact; catalog IDs, never display filenames, own installation. */
internal data class DownloadAsset(val id: String, val url: String, val bytes: Long, val sha256: String,
                                  val installedBytes: Long?, val ranges: List<LongRange> = emptyList(), val sourceBytes: Long? = null)

internal object DownloadAssets {
    const val MAGPIE_TOKENIZER = "voice-magpie-tokenizer"
    fun find(id: String): DownloadAsset? {
        if (id == MAGPIE_TOKENIZER) return com.sal7one.hearth.tts.magpie.MagpieCatalog.let { catalog ->
            DownloadAsset(id, "${catalog.source}/resolve/${catalog.revision}/magpie_tts_multilingual_357m.nemo",
                catalog.tokenizerSegmentsBytes, catalog.tokenizerSegmentsSha256, catalog.tokenizer.sumOf { it.bytes }, catalog.ranges, 1470208000L)
        }
        com.sal7one.transiber.models.SpeechComponents.part(id)?.let { return it.asset() }
        Ct2Package.part(id)?.let { return it.asset() }
        MarianPackage.part(id)?.let { return it.asset() }
        TranslationCatalog.models.firstOrNull { it.id == id }?.let {
            return DownloadAsset(id, it.url, it.bytes, it.sha256, it.bytes)
        }
        SpeechDownloads.find(id)?.let {
            return DownloadAsset(id, it.url, it.bytes, it.sha256, it.installationBytes)
        }
        SpeechArtifactCatalog.find(id)?.takeUnless { it.components != null }?.let {
            return DownloadAsset(id, it.url, it.bytes, it.sha256, it.installedBytes)
        }
        OcrCatalog.find(id)?.let { return DownloadAsset(id, it.url, it.bytes, it.sha256, it.bytes) }
        VoiceCatalog.find(id)?.let { return DownloadAsset(id, it.url, it.bytes, it.sha256, it.bytes) }
        return null
    }
}
