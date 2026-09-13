package com.sal7one.transiber.voice

internal enum class VoicePlaybackMode { DEFAULT, SYSTEM, CUSTOM }

/** A one-off Android playback must not overwrite the preferred custom engine. */
internal object VoiceSelection {
    fun customBackend(current: String, savedCustom: String?, networkAllowed: Boolean): String? {
        val selected = current.takeIf { it == "supertonic" || it == "remote" } ?: savedCustom
        return selected?.takeIf { it == "supertonic" || (it == "remote" && networkAllowed) }
    }

    fun backend(mode: VoicePlaybackMode, current: String, savedCustom: String?, networkAllowed: Boolean): String = when (mode) {
        VoicePlaybackMode.SYSTEM -> "system"
        VoicePlaybackMode.CUSTOM -> customBackend(current, savedCustom, networkAllowed)
            ?: error("Choose a default custom voice in Voices & read aloud first")
        VoicePlaybackMode.DEFAULT -> if (current == "remote" && !networkAllowed) "system" else current
    }
}
