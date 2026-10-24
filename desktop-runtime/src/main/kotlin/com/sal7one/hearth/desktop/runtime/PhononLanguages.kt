package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection

internal val PHONON_25_LANGUAGES: Set<String> =
    "en es fr de it pt nl pl ru uk ro cs sk bg hr sv da fi et lv lt hu sl mt el".split(' ').toSet()

/** Explicit checkpoint identity, never a filename guess or an engine-wide language assumption. */
internal fun phononMultilingual(selection: EngineSelection): Boolean =
    selection.modelId == "phonon-2.5" || selection.options["profile"] == "phonon-2.5"

internal fun phononLanguages(selection: EngineSelection): Set<String> =
    if (phononMultilingual(selection)) PHONON_25_LANGUAGES else setOf("en")

/** English-only weights provide fixed source identity; multilingual Auto provides no detected language. */
internal fun phononSource(selection: EngineSelection, source: String): String =
    withSupportedSpeechLanguage(source, phononLanguages(selection), if (phononMultilingual(selection)) "Phonon 2.5" else "Phonon 2") {
        if (it == "auto" && !phononMultilingual(selection)) "en" else it
    }
