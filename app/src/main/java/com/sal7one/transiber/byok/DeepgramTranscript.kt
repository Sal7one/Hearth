package com.sal7one.transiber.byok

import org.json.JSONObject

/** Collect stable segments while replacing only the current segment's hypothesis. */
internal class DeepgramTranscript {
    data class Update(val interim: String = "", val final: String? = null)

    private val settled = StringBuilder()

    fun accept(event: JSONObject): Update {
        val text = event.optJSONObject("channel")?.optJSONArray("alternatives")
            ?.optJSONObject(0)?.optString("transcript").orEmpty().trim()
        val end = event.optBoolean("speech_final")
        if (!event.optBoolean("is_final") && !end) {
            val interim = when {
                settled.isEmpty() -> text
                text.isEmpty() -> settled.toString()
                else -> "$settled $text"
            }
            return Update(interim = interim)
        }
        if (text.isNotEmpty()) {
            if (settled.isNotEmpty()) settled.append(' ')
            settled.append(text)
        }
        // A speaker can keep talking without an endpoint. Publish stable text before it grows too large.
        return if (end || settled.length >= MAX_PENDING_CHARS) Update(final = finish())
            else Update(interim = settled.toString())
    }

    fun finish(): String? = settled.toString().takeIf { it.isNotEmpty() }.also { settled.clear() }

    private companion object {
        const val MAX_PENDING_CHARS = 4096
    }
}
