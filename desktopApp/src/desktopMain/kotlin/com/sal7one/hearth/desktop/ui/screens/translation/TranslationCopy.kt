package com.sal7one.hearth.desktop.ui.screens.translation

/** Copy for translation settings. No quality claims, no glossary that does not exist. */
internal object TranslationCopy {
    const val EYEBROW = "Settings"
    const val TITLE = "Translation"
    const val LEDE =
        "Takes recognised text and rewrites it in another language. It is optional — captions still " +
            "work with this stage off."
    const val TRY = "Try a sentence"

    const val ENGINE = "Engine"
    const val ENGINE_DETAIL = "A local GGUF model, a connection you already have, or nothing."
    const val MODEL = "Model"
    const val MODEL_HINT = "Pinned size and SHA-256."
    const val LIBRARY = "Library"
    const val PAIR = "Default pair"
    const val PAIR_HINT = "Used when a feature does not override it."
    const val PROMPT = "Prompt"
    const val PROMPT_HINT = "Which instruction the model receives."
    const val PROMPT_NOTE =
        "The catalog picks the right protocol when you install a model. Changing it is for models that expect something else."

    const val ADVANCED = "Advanced"
    const val ADVANCED_DETAIL = "The prompt protocol and runtime are the ones Hearth already uses on Android."
    const val COMPUTE = "Compute"
    const val COMPUTE_HINT = "A GPU request that fails is an error, not a fallback."
    const val THREADS = "Threads"
    const val THREADS_HINT = "Ignored when the GPU is used."

    const val STATUS = "Status"
    const val READY = "Ready"
    const val NOT_CONFIGURED = "Not configured"
    const val ORDER = "Order matters"
    const val ORDER_DETAIL =
        "Translation runs after recognition, so the source language is whatever speech produced — not a guess."
    const val AUTO_WARNING =
        "With cloud speech on auto, pick the source language here — the detected language is not passed along."
    const val BUNDLE = "Bundle it"
    const val BUNDLE_DETAIL = "Save this translator with a speech model and a voice as one preset."
    const val OPEN_PRESETS = "Open presets"
}
