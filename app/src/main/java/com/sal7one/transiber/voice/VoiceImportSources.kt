package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.magpie.MagpieCatalog
import java.io.File

/** An authenticated range import publishes tokenizer members, never the transient range payload. */
internal fun voiceImportSources(models: VoiceModels,asset: VoiceAsset): List<Pair<String,File>> =
    if(asset.id=="voice-magpie-tokenizer") MagpieCatalog.tokenizer.map { tokenizer ->
        "voice-magpie-tokenizer-${tokenizer.filename}" to File(models.file(asset).parentFile,"tokenizer/${tokenizer.filename}")
    } else listOf(asset.id to models.file(asset))
