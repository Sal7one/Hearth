package com.sal7one.transiber.voice

/** Pure preflight used by every provider; never starts an engine or substitutes another stage. */
internal object LocalVoiceReadiness {
    fun ready(
        voice: String,
        gender: VoiceGender,
        localAllowed: Boolean,
        inventory: List<String>,
        verifiedGender: Boolean,
        runtimeAvailable: () -> Boolean,
        packageReady: () -> Boolean,
    ): Boolean = localAllowed && voice in inventory &&
        (verifiedGender || gender == VoiceGender.ANY) && runtimeAvailable() && packageReady()
}
