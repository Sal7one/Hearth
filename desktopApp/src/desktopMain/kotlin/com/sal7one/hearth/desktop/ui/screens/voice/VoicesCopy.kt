package com.sal7one.hearth.desktop.ui.screens.voice

/** Copy for voice settings. It says what is measured and what has not been judged. */
internal object VoicesCopy {
    const val EYEBROW = "Settings"
    const val TITLE = "Voices"
    const val LEDE =
        "Speaks the caption back out loud. Off by default — reading is faster, and a voice can talk " +
            "over the audio you are captioning."
    const val STOP_ALL = "Stop all audio"

    const val ENGINE = "Engine"
    const val ENGINE_DETAIL = "Choose a local model, an installed system voice, or a provider connection."
    const val STYLE = "Style"
    const val STYLE_DETAIL = "Female F1–F5, male M1–M5."
    const val PREVIEW = "Preview"
    const val PREVIEW_SENTENCE = "This sentence is read in the selected voice before you hear it in a session."
    const val PLAY = "Play"

    const val WHEN = "When it speaks"
    const val WHEN_DETAIL = "Independent of recognising and showing captions."
    const val READ_ALOUD = "Read captions aloud"
    const val READ_ALOUD_HINT = "Each finished line is spoken once."
    const val SPEAK = "Speak"
    const val SPEAK_HINT = "Which line is voiced when both exist."
    const val SPEED = "Speed"
    const val SPEED_HINT = "Faster voices lose clarity in long sentences."
    const val STEPS = "Detail"
    const val STEPS_HINT = "More sampling steps cost CPU time and change little."

    const val STATUS = "Status"
    const val READY = "Ready"
    const val OFF = "Off"
    const val NEEDS_LISTEN = "Needs a listen"
    const val NEEDS_LISTEN_DETAIL =
        "The engine produces non-silent audio, but pronunciation has not been judged by a person. Treat the styles as a starting point."
    const val FILES = "Voice files"
    const val FILES_DETAIL = "Generated WAVs live in the session folder until you save them."
    const val OPEN_FILES = "Open files & locations"
}
