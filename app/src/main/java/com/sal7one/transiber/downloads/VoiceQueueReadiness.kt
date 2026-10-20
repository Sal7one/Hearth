package com.sal7one.transiber.downloads

import android.content.Context
import com.sal7one.transiber.voice.LocalVoiceFamilies
import com.sal7one.transiber.voice.VoiceCatalog
import com.sal7one.transiber.voice.VoiceSettings

/** Queue completion is distinct from a complete executable voice (Magpie also needs its tokenizer). */
internal fun voiceQueueReady(context: Context, group: DownloadQueueGroup): Boolean {
    val family = group.voiceFamily ?: return false
    val voices = LocalVoiceFamilies.voices(family)
    val queuedVoice = if (family == "supertonic") group.required.keys.asSequence().mapNotNull(VoiceCatalog::find)
        .map { it.filename.removeSuffix(".json") }.firstOrNull { it in voices } else null
    val selected = VoiceSettings.choice(context)
    val voice = queuedVoice ?: VoiceSettings.familyVoice(context, family).takeIf { it in voices }
        ?: voices.firstOrNull() ?: return false
    return LocalVoiceFamilies.ready(context, selected.copy(backend = "supertonic", family = family, voice = voice))
}
