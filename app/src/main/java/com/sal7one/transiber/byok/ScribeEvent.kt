package com.sal7one.transiber.byok

import org.json.JSONObject

/** Timestamp events supplement a committed transcript; they must not duplicate it. */
internal sealed interface ScribeEvent {
    data object Connected : ScribeEvent
    data class Partial(val text: String) : ScribeEvent
    data class Final(val text: String) : ScribeEvent
    data class Error(val message: String) : ScribeEvent
    data object Ignore : ScribeEvent
    companion object {
        fun parse(raw: String): ScribeEvent {
            val event = JSONObject(raw)
            val kind = event.optString("message_type")
            return when (kind) {
                "session_started" -> Connected
                "partial_transcript" -> Partial(event.getString("text"))
                "committed_transcript" -> Final(event.getString("text"))
                "committed_transcript_with_timestamps" -> Ignore
                else -> if (event.has("error") || kind.endsWith("_error") || kind in setOf("rate_limited", "quota_exceeded"))
                    Error("$kind: ${event.opt("error") ?: event.opt("message") ?: event}") else Ignore
            }
        }
    }
}
