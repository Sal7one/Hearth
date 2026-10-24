package com.sal7one.transiber.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** English is the SDK's built-in pivot; every other language needs its own installed pack. */
internal fun mlKitRequiredPacks(source: String?, target: String?, supported: Set<String>): Set<String> =
    listOfNotNull(source, target).map(::mlKitLanguageCode).filterTo(linkedSetOf()) { it != "en" && it in supported }

internal fun mlKitLanguageCode(code: String): String = when (val base = code.lowercase().replace('_', '-').substringBefore('-')) {
    "tl" -> "fil"
    else -> base
}

internal data class MlKitPackReadiness(val required: Set<String>, val missing: Set<String>, val supported: Boolean,
    val automaticSource: Boolean, val checked: Boolean) {
    val ready: Boolean get() = checked && supported && missing.isEmpty()
}

internal fun mlKitPackReadiness(source: String?, target: String?, supported: Set<String>, installed: Set<String>?): MlKitPackReadiness {
    val from = source?.let(::mlKitLanguageCode); val to = target?.let(::mlKitLanguageCode)
    val automatic = source in setOf(null, "", "auto", "model", "und", "mul")
    val valid = to != null && to in supported && (automatic || (from != null && from in supported)) && from != to
    val required = mlKitRequiredPacks(source, target, supported)
    return MlKitPackReadiness(required, required - installed.orEmpty(), valid, automatic, installed != null)
}

/** SDK operations stay injectable so download completion and installed-model discovery are tested together. */
internal class MlKitLanguagePacks(
    private val supported: () -> Set<String>,
    private val readInstalled: suspend () -> Set<String>,
    private val downloadPack: suspend (String, Boolean) -> Unit,
    private val removePack: suspend (String) -> Unit,
) {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    suspend fun installed(): Set<String> = readInstalled().map(::mlKitLanguageCode).filterTo(mutableSetOf()) { it in supported() } + "en"

    suspend fun download(code: String, wifiOnly: Boolean = true) {
        val language = mlKitLanguageCode(code)
        require(language in supported()) { "ML Kit does not support $code" }
        if (language == "en") return
        try {
            downloadPack(language, wifiOnly)
            check(language in installed()) { "ML Kit finished downloading $language, but its language pack is still unavailable. Retry the download." }
        } finally { changes.update { it + 1 } }
    }

    suspend fun downloadPair(source: String, target: String, wifiOnly: Boolean = true) {
        val from = mlKitLanguageCode(source); val to = mlKitLanguageCode(target)
        require(from in supported() && to in supported() && from != to) { "ML Kit does not support $source → $target" }
        val missing = mlKitRequiredPacks(from, to, supported()) - installed()
        for (language in missing) download(language, wifiOnly)
    }

    suspend fun remove(code: String) {
        val language = mlKitLanguageCode(code)
        require(language in supported()) { "ML Kit does not support $code" }
        if (language == "en") return
        try {
            removePack(language)
            check(language !in installed()) { "ML Kit did not remove the $language language pack. Retry removal." }
        } finally { changes.update { it + 1 } }
    }
}
