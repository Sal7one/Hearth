package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.magpie.MagpieCatalog

/** Family-specific preflight; it never starts an engine or substitutes another speech stage. */
internal object LocalVoiceReadiness {
    fun ready(
        family: String,
        voice: String,
        gender: VoiceGender,
        localAllowed: Boolean,
        supertonicReady: () -> Boolean,
        magpieAvailable: () -> Boolean,
        magpieReady: () -> Boolean,
    ): Boolean {
        if (!localAllowed) return false
        return when (family) {
            "supertonic" -> supertonicReady()
            // Pinned Magpie speaker names have no verified gender metadata.
            "magpie" -> gender == VoiceGender.ANY && voice in MagpieCatalog.speakers &&
                magpieAvailable() && magpieReady()
            else -> false
        }
    }
}
