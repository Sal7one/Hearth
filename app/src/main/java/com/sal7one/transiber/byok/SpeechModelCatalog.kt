package com.sal7one.transiber.byok

import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Capability evidence must describe transcription, not merely audio input. */
internal object SpeechModelCatalog {
    enum class Role(val label: String, val batchSelectable: Boolean) {
        TRANSCRIPTION("Batch transcription", true),
        LIVE("Live transcription · streaming engine", false),
        TRANSLATION("Live translation · streaming engine", false),
        DIARIZATION("Speaker labels · not integrated", false),
    }

    data class Model(
        val id: String,
        val name: String,
        val createdSeconds: Long?,
        val pricing: String,
        val role: Role,
    ) {
        val created: String get() = createdSeconds?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(it * 1000))
        } ?: "Unknown"
    }

    fun parse(body: String): List<Model> {
        val data = JSONObject(body).getJSONArray("data")
        return (0 until data.length()).mapNotNull { index ->
            val model = data.getJSONObject(index)
            val id = model.getString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = id.substringAfterLast('/').lowercase(Locale.ROOT)
            val role = when {
                slug == "gpt-realtime-translate" || slug.startsWith("gpt-realtime-translate-") -> Role.TRANSLATION
                slug == "gpt-live-transcribe" || slug.startsWith("gpt-live-transcribe-") ||
                    slug == "gpt-realtime-whisper" || slug.startsWith("gpt-realtime-whisper-") -> Role.LIVE
                slug.startsWith("gpt-4o-transcribe-diarize") -> Role.DIARIZATION
                slug == "whisper-1" || slug.startsWith("whisper-large-v3") ||
                    Regex("gpt-(4o-(mini-)?)?transcribe(-[0-9]{4}-[0-9]{2}-[0-9]{2})?").matches(slug) -> Role.TRANSCRIPTION
                model.optJSONObject("architecture")?.optJSONArray("output_modalities")?.let { modalities ->
                    (0 until modalities.length()).any { modalities.optString(it) == "transcription" }
                } == true -> Role.TRANSCRIPTION
                else -> return@mapNotNull null
            }
            val created = model.optLong("created", 0).takeIf { it in 1..253402300799L }
            Model(id, model.optString("name").ifBlank { id }, created, price(model), role)
        }.distinctBy { it.id }.sortedWith(
            compareByDescending<Model> { it.createdSeconds ?: Long.MIN_VALUE }.thenBy { it.id },
        )
    }

    private fun price(model: JSONObject): String {
        val pricing = model.optJSONObject("pricing") ?: return "Price not provided"
        // STT catalogs reuse prompt for per-second, per-minute and token rates.
        // The list response does not supply that billing unit. Never guess it.
        val rates = listOf("prompt" to "input", "completion" to "output", "request" to "request")
        val values = rates.mapNotNull { (key, label) ->
            val amount = pricing.optString(key).toBigDecimalOrNull()
                ?.takeIf { it >= BigDecimal.ZERO } ?: return@mapNotNull null
            label + " $" + amount.stripTrailingZeros().toPlainString()
        }
        return if (values.isEmpty()) "Price not provided"
            else values.joinToString(" · ") + " (billing unit not supplied)"
    }
}
