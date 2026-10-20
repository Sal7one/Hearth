package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.supertonic.SupertonicCatalog
import com.sal7one.hearth.tts.magpie.MagpieCatalog

internal data class VoiceAsset(val id: String,val filename: String,val path: String,val bytes: Long,val sha256: String,val url: String,val license: String=SupertonicCatalog.license)
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
    val assets = supertonicAssets + magpieAssets
    fun find(id: String) = assets.firstOrNull {it.id==id}
    fun file(name: String) = supertonicAssets.first {it.filename==name}
    val core get() = supertonicAssets.filter {it.path.startsWith("onnx/")}
}
