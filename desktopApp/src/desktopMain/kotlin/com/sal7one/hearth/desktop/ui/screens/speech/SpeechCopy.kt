package com.sal7one.hearth.desktop.ui.screens.speech

/** Copy for the speech settings. It explains the stage, not the model format. */
internal object SpeechCopy {
    const val EYEBROW = "Settings"
    const val TITLE = "Speech recognition"
    const val LEDE =
        "Turns sound into text. It runs before everything else, so the language and the input " +
            "device matter more than the model size."
    const val TEST_FILE = "Test with a file"
    const val TEST_MIC = "Test microphone"

    const val ENGINE = "Engine"
    const val ENGINE_DETAIL = "Where the listening happens."
    const val MODEL = "Model"
    const val MODEL_HINT = "Pinned and verified on install."
    const val LIBRARY = "Library"
    const val LANGUAGE = "Language"
    const val LANGUAGE_HINT = "Explicit is more accurate; auto costs a moment."
    const val INPUT = "Microphone device"
    const val INPUT_HINT = "For microphone input. Choose Mac audio in Captions to capture streams and videos."
    const val REFRESH = "Refresh"
    const val INPUT_NOTE = "Mac audio uses the system output instead of this microphone device."
    const val RESPONSIVENESS = "Responsiveness"
    const val RESPONSIVENESS_HINT = "How long Hearth waits before committing a line."

    const val ADVANCED = "Advanced"
    const val ADVANCED_DETAIL = "Only if the defaults are not right for your machine."
    const val COMPUTE = "Compute"
    const val COMPUTE_HINT = "A GPU request that fails is an error, not a fallback."
    const val THREADS = "Threads"
    const val THREADS_HINT = "CPU worker threads; GPU backends may still use CPU work."

    const val STATUS = "Status"
    const val READY = "Ready"
    const val NOT_CONFIGURED = "Not configured"
    const val HOW_IT_LISTENS = "How it listens"
    const val HOW_IT_LISTENS_DETAIL =
        "Hearth recognises utterance windows, not a token stream. A line is published when the window closes, then translation follows."
    const val ENGLISH_ONLY_WARNING =
        "An English-only model (.en) cannot produce Arabic. Hearth will say so instead of returning empty text."

    const val AUTO_LABEL = "Detect automatically"
    const val OFF_ENGINE = "Not configured"
}
