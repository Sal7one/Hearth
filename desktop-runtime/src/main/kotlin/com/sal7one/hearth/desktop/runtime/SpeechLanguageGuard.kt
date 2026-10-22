package com.sal7one.hearth.desktop.runtime

import java.util.Locale

/** Explicit language declarations must be within the checkpoint's actual inventory. Auto makes no declaration. */
internal fun <T> withSupportedSpeechLanguage(source: String, supported: Set<String>, label: String, create: (String) -> T): T {
    val language = source.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
    require(language == "auto" || language in supported) { "$label does not support the declared language: $source" }
    return create(language)
}
