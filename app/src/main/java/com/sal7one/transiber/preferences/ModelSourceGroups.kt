package com.sal7one.transiber.preferences

import com.sal7one.transiber.downloads.FileDownload

internal data class ModelSourceGroup(val id: String,val label: String,val components: List<ModelBindingCheck>) {
    val missing get() = components.filterNot { it.available }
    val needsRepair get() = missing.any { it.installationRecorded }
}

/** A tokenizer dictionary is a component, not another installed model family. */
internal fun modelSourceGroups(checks: List<ModelBindingCheck>): List<ModelSourceGroup> = checks.groupBy { check ->
    when {
        check.modelId.startsWith("voice-magpie-") -> "voice-magpie"
        check.modelId.startsWith("voice-ming-local-") -> "voice-ming"
        check.modelId.startsWith("voice-chatterbox-v3-") -> "voice-chatterbox"
        check.modelId.startsWith("voice-omnivoice-") -> "voice-omnivoice"
        check.modelId.startsWith("voice-moss-nano-") -> "voice-moss"
        check.modelId.startsWith("voice-oute-") -> "voice-oute"
        else -> check.modelId
    }
}.map { (id,components) -> ModelSourceGroup(id,when(id) {
    "voice-magpie" -> "MagpieTTS v2607"
    "voice-ming" -> "Ming local TTS"
    "voice-chatterbox" -> "Chatterbox Multilingual V3"
    "voice-omnivoice" -> "OmniVoice"
    "voice-moss" -> "MOSS-TTS Nano"
    "voice-oute" -> "OuteTTS"
    else -> id
},components) }.sortedWith(compareByDescending<ModelSourceGroup> { it.needsRepair }.thenByDescending { it.missing.isNotEmpty() }.thenBy { it.label })

/** Reinstall authenticates the saved original through the ordinary worker; no redownload is assumed. */
internal fun recoverableModelDownloads(checks: List<ModelBindingCheck>, downloads: List<FileDownload>): List<Long> {
    val missing=checks.filterNot { it.available }.map { it.modelId }.toSet()
    return downloads.filter { item -> item.id<0 && item.complete && !item.active &&
        (item.modelId in missing || item.modelId == "voice-magpie-tokenizer" && missing.any { it.startsWith("voice-magpie-tokenizer-") })
    }.map { it.id }.distinct()
}
