package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.supertonic.SupertonicCatalog
import com.sal7one.hearth.tts.magpie.MagpieCatalog
import com.sal7one.hearth.tts.omnivoice.OmniVoiceCatalog
import com.sal7one.hearth.tts.moss.MossCatalog
import com.sal7one.hearth.tts.oute.OuteCatalog
import com.sal7one.hearth.tts.chatterbox.ChatterboxCatalog
import com.sal7one.hearth.tts.chatterbox.ChatterboxPerth
import com.sal7one.hearth.tts.chatterbox.ChatterboxGraph
import com.sal7one.hearth.tts.ming.MingCatalog

internal data class VoiceAsset(val id: String,val filename: String,val path: String,val bytes: Long,val sha256: String,val url: String,val license: String=SupertonicCatalog.license,
    val installedPath: String=path,val installedBytes: Long=bytes,val installedSha256: String=sha256) {
    val family: String get() = when { id.startsWith("voice-magpie-") -> "magpie"; id.startsWith("voice-omnivoice-") -> "omnivoice"; id.startsWith("voice-moss-nano-") -> "moss-nano"; id.startsWith("voice-oute-") -> "oute"; id.startsWith("voice-chatterbox-v3-") -> "chatterbox-v3"; id.startsWith("voice-ming-local-") -> MingCatalog.FAMILY; else -> "supertonic" }
}
/** App and reusable article providers share the same pinned component hashes. */
internal object VoiceCatalog {
    const val modelCard = SupertonicCatalog.modelCard
    const val projectUrl = SupertonicCatalog.projectUrl
    const val license = SupertonicCatalog.license
    const val revision = SupertonicCatalog.revision
    val filesUrl get() = SupertonicCatalog.filesUrl
    val licenseUrl get() = SupertonicCatalog.licenseUrl
    val languages get() = SupertonicCatalog.languages
    val voices get() = SupertonicCatalog.voices
    val supertonicAssets = SupertonicCatalog.assets.map { VoiceAsset(it.id,it.filename,it.path,it.bytes,it.sha256,it.url) }
    val magpieAssets = MagpieCatalog.weights.mapIndexed {index,asset ->
        val repo=if(index==0)"nvidia/magpie_tts_multilingual_357m" else "nvidia/nemo-nano-codec-22khz-1.89kbps-21.5fps"
        val pin=if(index==0)MagpieCatalog.revision else "fc00890b604aa2de298d2641ffc6c5f6caf8c4d7"
        VoiceAsset("voice-magpie-v2607-${if(index==0)"generator" else "codec"}",asset.filename,asset.filename,asset.bytes,asset.sha256,"https://huggingface.co/$repo/resolve/$pin/${asset.filename}","NVIDIA Open Model License")
    }
    val omnivoiceAssets = OmniVoiceCatalog.assets.mapIndexed { index, asset ->
        VoiceAsset("voice-omnivoice-${if(index==0)"generator" else "codec"}",asset.filename,asset.filename,asset.bytes,asset.sha256,"${OmniVoiceCatalog.source}/resolve/${OmniVoiceCatalog.revision}/${asset.filename}",OmniVoiceCatalog.license)
    }
    val mossAssets = MossCatalog.assets.mapIndexed { index, asset -> VoiceAsset("voice-moss-nano-$index",asset.filename,asset.filename,asset.bytes,asset.sha256,asset.url,MossCatalog.license) }
    val outeAssets = OuteCatalog.assets.mapIndexed { index, asset -> VoiceAsset("voice-oute-$index",asset.filename,asset.filename,asset.bytes,asset.sha256,asset.url,OuteCatalog.license) }
    val chatterboxAssets = ChatterboxCatalog.assets.mapIndexed { index, asset -> VoiceAsset("voice-chatterbox-v3-$index",asset.filename.substringAfterLast('/'),asset.filename,asset.bytes,asset.sha256,asset.url,ChatterboxCatalog.license,
        if(asset.filename==ChatterboxGraph.SOURCE_FILENAME)ChatterboxGraph.FILENAME else asset.filename,
        if(asset.filename==ChatterboxGraph.SOURCE_FILENAME)ChatterboxGraph.OUTPUT_BYTES else asset.bytes,
        if(asset.filename==ChatterboxGraph.SOURCE_FILENAME)ChatterboxGraph.OUTPUT_SHA256 else asset.sha256) } +
        VoiceAsset("voice-chatterbox-v3-perth","perth-source.pth.tar","perth-source.pth.tar",ChatterboxPerth.SOURCE_BYTES,ChatterboxPerth.SOURCE_SHA256,ChatterboxPerth.SOURCE_URL,"MIT",ChatterboxPerth.FILENAME,ChatterboxPerth.OUTPUT_BYTES,ChatterboxPerth.OUTPUT_SHA256)
    // The experimental Ming bundle installs flat; MingModels links the runtime's parts/ layout.
    val mingAssets = MingCatalog.assets.mapIndexed { index, asset ->
        VoiceAsset("voice-ming-local-$index", asset.filename, asset.filename, asset.bytes, asset.sha256, asset.url, MingCatalog.LICENSE)
    }
    val assets = supertonicAssets + magpieAssets + omnivoiceAssets + mossAssets + outeAssets + chatterboxAssets + mingAssets
    fun familyAssets(family: String) = assets.filter { it.family == family }
    fun find(id: String) = assets.firstOrNull {it.id==id}
    fun file(name: String) = supertonicAssets.first {it.filename==name}
    val core get() = supertonicAssets.filter {it.path.startsWith("onnx/")}
}
