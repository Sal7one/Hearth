package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.supertonic.SupertonicCatalog

internal typealias VoiceAsset = com.sal7one.hearth.tts.supertonic.SupertonicAsset

/** App and reusable article provider share one pinned asset inventory. */
internal object VoiceCatalog {
    const val modelCard = SupertonicCatalog.modelCard
    const val projectUrl = SupertonicCatalog.projectUrl
    const val license = SupertonicCatalog.license
    const val revision = SupertonicCatalog.revision
    val filesUrl get() = SupertonicCatalog.filesUrl
    val licenseUrl get() = SupertonicCatalog.licenseUrl
    val languages get() = SupertonicCatalog.languages
    val voices get() = SupertonicCatalog.voices
    val assets get() = SupertonicCatalog.assets
    fun find(id: String) = SupertonicCatalog.find(id)
    fun file(name: String) = SupertonicCatalog.file(name)
    val core get() = SupertonicCatalog.core
}
