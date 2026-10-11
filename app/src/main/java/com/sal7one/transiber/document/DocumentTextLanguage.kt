package com.sal7one.transiber.document

import java.util.Locale

/** Text-layer documents may contain languages the selected image reader cannot recognize. */
internal object DocumentTextLanguage {
    val choices: Set<String> = Locale.getISOLanguages().toSet()
    fun defaultFor(profile: String, languages: Set<String>): String = when {
        profile == "arabic" && "ar" in languages -> "ar"
        profile == "cjk" && "zh" in languages -> "zh"
        "en" in languages -> "en"
        else -> languages.sorted().firstOrNull { it in choices } ?: "en"
    }
    fun read(saved: String?, profile: String, languages: Set<String>): String =
        saved?.takeIf { it in choices } ?: defaultFor(profile, languages)
}
