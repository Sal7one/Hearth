package com.sal7one.transiber.voice

import android.content.Context
import com.sal7one.hearth.tts.magpie.MagpieSpeechEngine
import com.sal7one.transiber.runtime.LocalModelPolicy

/** Check the running process, not just the device's advertised64-bit ABI list. */
internal object MagpiePolicy {
    fun available(context: Context): Boolean = LocalModelPolicy.allowed(context) && MagpieSpeechEngine.available()
    fun requireAllowed(context: Context, feature: String = "Magpie model use") {
        LocalModelPolicy.requireAllowed(context,feature)
        check(MagpieSpeechEngine.available()){"Magpie requires the packaged arm64 native runtime in this app process ($feature)"}
    }
}
