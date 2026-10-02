package com.sal7one.transiber.byok

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/** Pure wire adapters. No credential is included in a URL or diagnostic string. */
internal object CloudVoiceProtocol {
    const val AUDIO_LIMIT = 12 * 1024 * 1024
    const val JSON_AUDIO_LIMIT = 17 * 1024 * 1024
    const val CATALOG_LIMIT = 2 * 1024 * 1024
    class Request(val url: String, val header: String, val body: String?)
    data class Audio(val bytes: ByteArray, val extension: String)
    data class Voice(val id: String, val label: String)
    data class VoicePage(val voices: List<Voice>, val next: String?)

    fun speech(config: CloudVoiceConfig, text: String): Request {
        val c = config.validated()
        require(text.isNotBlank() && text.length <= 5000) { "Read aloud accepts 1–5000 characters" }
        return when(c.provider) {
            CloudVoiceProvider.GEMINI -> if (c.usesInteractions()) {
                val content = JSONObject().put("type", "text").put("text", text)
                if (c.instructions.isNotBlank()) content.put("annotations", JSONArray().put(
                    JSONObject().put("type", "speech_metadata").put("style", c.instructions)))
                Request(c.endpoint + "/interactions", "x-goog-api-key", JSONObject()
                    .put("model", c.model).put("input", JSONArray().put(JSONObject().put("type", "user_input")
                        .put("content", JSONArray().put(content))))
                    .put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/wav")
                        .put("sample_rate", 24000).put("delivery", "inline"))
                    .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", c.voice))))
                    .put("stream", false).put("store", false).toString())
            } else {
                require(c.model.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid Gemini model ID" }
                Request(c.endpoint + "/models/${c.model}:generateContent", "x-goog-api-key", JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                            JSONObject().put("voiceName", c.voice))))).toString())
            }
            CloudVoiceProvider.ELEVENLABS -> Request(c.endpoint + "/text-to-speech/${encode(c.voice)}?output_format=mp3_44100_128",
                "xi-api-key", JSONObject().put("text", text).put("model_id", c.model)
                    .put("voice_settings", JSONObject().put("speed", c.speed.toString().toDouble())).toString())
            else -> {
                val body = JSONObject().put("model", c.model).put("input", text).put("response_format", "mp3")
                if (c.voice.isNotBlank()) body.put("voice", c.voice)
                if (c.supportsSpeed()) body.put("speed", c.speed.toString().toDouble())
                if (c.supportsInstructions() && c.instructions.isNotBlank()) body.put("instructions", c.instructions)
                Request(c.endpoint.let { if (it.endsWith("/audio/speech")) it else "$it/audio/speech" }, "Authorization", body.toString())
            }
        }
    }

    fun audio(config: CloudVoiceConfig, raw: ByteArray, contentType: String?): Audio {
        check(raw.isNotEmpty()) { "TTS provider returned empty audio" }
        val json = contentType.orEmpty().contains("json", true) || raw.firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()
        if (!json) {
            check(config.provider != CloudVoiceProvider.GEMINI) { "Google returned a non-JSON response" }
            check(raw.size <= AUDIO_LIMIT) { "TTS audio exceeds size limit" }
            check(contentType == null || contentType.startsWith("audio/", true) || contentType.startsWith("application/octet-stream", true)) {
                "TTS provider returned ${contentType.orEmpty()} instead of audio"
            }
            return Audio(raw, if (raw.take(4).toByteArray().contentEquals("RIFF".toByteArray())) "wav" else "mp3")
        }
        val root = JSONObject(raw.toString(Charsets.UTF_8))
        root.optJSONObject("error")?.let { error("TTS provider error: ${it.optString("message", it.toString()).take(400)}") }
        val block = if (config.provider == CloudVoiceProvider.GEMINI) {
            if (config.usesInteractions()) {
                // output_audio is an SDK convenience property; REST returns steps[].content[].
                val blocks = mutableListOf<JSONObject>()
                val steps = root.optJSONArray("steps") ?: error("Google response has no steps")
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    if (step.optString("type") != "model_output") continue
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) content.getJSONObject(j).takeIf { it.optString("type") == "audio" }?.let(blocks::add)
                }
                blocks.lastOrNull() ?: error("Google response has no inline audio (${root.optString("status")})")
            } else {
                val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: error("Google returned no candidate")
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: error("Google returned no audio (${candidate.optString("finishReason")})")
                (0 until parts.length()).firstNotNullOfOrNull { parts.optJSONObject(it)?.optJSONObject("inlineData") }
                    ?: error("Google returned no inline audio (${candidate.optString("finishReason")})")
            }
        } else root.optJSONObject("audio") ?: root.takeIf { it.has("data") }
            ?: error("TTS response JSON had no audio data")
        val data = block.optString("data")
        check(data.isNotBlank()) { "TTS response JSON had no audio data" }
        val bytes = Base64.getDecoder().decode(data)
        check(bytes.isNotEmpty() && bytes.size <= AUDIO_LIMIT) { "TTS decoded audio is empty or exceeds size limit" }
        val mime = block.optString("mime_type", block.optString("mimeType", block.optString("format", "mp3")))
        return when {
            mime.startsWith("audio/wav", true) || mime == "wav" -> {
                check(bytes.size >= 44 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                    bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "Invalid provider WAV audio" }
                Audio(bytes, "wav")
            }
            config.provider == CloudVoiceProvider.GEMINI && mime.startsWith("audio/L16", true) -> {
                val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt()
                    ?: block.optInt("sample_rate", 24000)
                check(rate == 24000) { "Unsupported Google PCM sample rate: $rate" }
                Audio(WavEncoder.wrapPcm16(bytes, rate), "wav")
            }
            mime in setOf("mp3", "audio/mp3", "audio/mpeg") -> Audio(bytes, "mp3")
            else -> error("Unsupported TTS audio format: $mime")
        }
    }

    fun modelsRequest(c: CloudVoiceConfig): Request = when(c.provider) {
        CloudVoiceProvider.OPENROUTER -> Request(c.provider.endpoint + "/models?output_modalities=speech", "Authorization", null)
        CloudVoiceProvider.ELEVENLABS -> Request(c.provider.endpoint + "/models", "xi-api-key", null)
        else -> error("This provider has no model discovery adapter")
    }
    fun models(provider: CloudVoiceProvider, raw: String): List<Pair<String, String>> {
        val array = if (provider == CloudVoiceProvider.OPENROUTER) JSONObject(raw).getJSONArray("data") else JSONArray(raw)
        require(array.length() <= 10000) { "Model catalog exceeds entry limit" }
        return (0 until array.length()).mapNotNull { i ->
            val model = array.getJSONObject(i)
            val speech = if (provider == CloudVoiceProvider.OPENROUTER) {
                val modalities = model.optJSONObject("architecture")?.optJSONArray("output_modalities")
                modalities != null && (0 until modalities.length()).any { modalities.optString(it) == "speech" }
            } else model.optBoolean("can_do_text_to_speech")
            if (!speech) null else {
                val id = model.getString(if (provider == CloudVoiceProvider.OPENROUTER) "id" else "model_id")
                require(id.isNotBlank() && id.length <= 200 && !id.any(Char::isISOControl)) { "Invalid catalog model ID" }
                id to model.optString("name", id).take(200)
            }
        }.distinctBy { it.first }.sortedBy { it.second }
    }
    fun voicesRequest(next: String? = null): Request = Request("https://api.elevenlabs.io/v2/voices?page_size=100&include_total_count=false" +
        (next?.let { "&next_page_token=${encode(it)}" } ?: ""), "xi-api-key", null)
    fun voices(raw: String): VoicePage {
        val root = JSONObject(raw); val array = root.getJSONArray("voices")
        require(array.length() <= 500) { "Voice page exceeds entry limit" }
        val voices = (0 until array.length()).map {
            val v = array.getJSONObject(it); val id = v.getString("voice_id")
            require(id.isNotBlank() && id.length <= 200 && !id.any(Char::isISOControl)) { "Invalid catalog voice ID" }
            val gender = v.optJSONObject("labels")?.optString("gender").orEmpty()
            Voice(id, v.optString("name", id).take(200) + if (gender.isBlank()) "" else " · ${gender.take(40)}")
        }.distinctBy { it.id }
        val next = if (root.optBoolean("has_more")) root.optString("next_page_token").takeIf { it.isNotBlank() && it != "null" }
            ?: error("Voice catalog has more entries but no page token") else null
        require(next == null || next.length <= 2048) { "Invalid voice page token" }
        return VoicePage(voices, next)
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
