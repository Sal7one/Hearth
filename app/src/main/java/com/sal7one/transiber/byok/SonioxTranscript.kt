package com.sal7one.transiber.byok

import org.json.JSONObject

/** Final tokens append once; non-final suffixes are replaced on every response.
 * Keep independent source/translation runs, never invent a one-to-one pairing. */
internal class SonioxTranscript {
    private var id = 0L
    private var revision = 0L
    private var text = StringBuilder()
    private var translated = false
    private var language: String? = null
    data class Result(val updates: List<CloudCaptionUpdate>, val partial: String, val translation: String)
    fun accept(event: JSONObject): Result {
        val out = mutableListOf<CloudCaptionUpdate>()
        val partial = StringBuilder(); val mt = StringBuilder()
        fun emit(complete: Boolean) {
            if (text.isNotBlank()) out += CloudCaptionUpdate(id, ++revision, text.toString().trim(), language, translated, complete)
        }
        fun finish() { emit(true); text = StringBuilder(); id++; revision = 0; language = null }
        val tokens = event.optJSONArray("tokens")
        for (i in 0 until (tokens?.length() ?: 0)) {
            val token = tokens!!.getJSONObject(i)
            val value = token.getString("text")
            if (value == "<end>" || value == "<fin>") {
                if (token.optBoolean("is_final")) finish()
                continue
            }
            val isTranslation = token.optString("translation_status") == "translation"
            if (!token.optBoolean("is_final")) {
                (if (isTranslation) mt else partial).append(value)
                continue
            }
            val lang = token.optString("language").takeIf { it.isNotBlank() }
            if (text.isNotEmpty() && (translated != isTranslation || (lang != null && language != null && lang != language))) finish()
            translated = isTranslation; language = lang ?: language
            text.append(value)
            check(text.length <= 16000) { "Soniox caption exceeds 16000 characters" }
        }
        if (event.optBoolean("finished")) finish() else emit(false)
        return Result(out, partial.toString(), mt.toString())
    }
}
