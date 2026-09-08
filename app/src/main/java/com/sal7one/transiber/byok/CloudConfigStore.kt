package com.sal7one.transiber.byok

import android.content.Context

/**
 * Cloud provider configuration (NETWORK CODE — play distribution only).
 *
 * The BYOK client is endpoint-agnostic: provider presets fill the base URL
 * and model ids, and every value is user-editable. An OpenAI key on the
 * OpenAI endpoint, an OpenRouter key on the OpenRouter endpoint, or any
 * OpenAI-compatible gateway on a custom URL.
 */
object CloudConfigStore {

    private const val PREFS = "byok_cloud_config"

    /** How cloud STT runs: batch (OpenRouter/OpenAI-compatible REST) or
     * TRUE streaming (WebSocket interim results) per provider. Groq is
     * deliberately absent — verified 2026-08: Groq STT is REST-only, use
     * its OpenAI-compatible endpoint (https://api.groq.com/openai/v1) as a
     * Custom batch provider instead. */
    enum class SttMode(val label: String) {
        BATCH("Batch"),
        STREAMING_DEEPGRAM("Streaming · Deepgram"),
        STREAMING_OPENAI("Streaming · OpenAI"),
        STREAMING_ASSEMBLYAI("Streaming · AssemblyAI"),
    }

    fun sttMode(context: Context): SttMode = runCatching {
        SttMode.valueOf(prefs(context).getString("stt_mode", SttMode.BATCH.name)!!)
    }.getOrDefault(SttMode.BATCH)

    fun setSttMode(context: Context, mode: SttMode) {
        prefs(context).edit().putString("stt_mode", mode.name).apply()
    }

    enum class Provider(val label: String, val baseUrl: String, val sttModel: String, val ttsModel: String, val ttsVoice: String) {
        OPENAI("OpenAI", "https://api.openai.com/v1", "whisper-1", "tts-1", "alloy"),
        OPENROUTER(
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            // Fastest STT on OpenRouter (verified 2026-08): NVIDIA's 0.6B
            // streaming ASR — sub-100ms-class chunk latency at
            // $0.00000333/sec (~$0.012/audio-hour).
            "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
            "google/gemini-3.1-flash-tts-preview",
            "alloy",
        ),
        CUSTOM("Custom", "", "", "", "alloy"),
    }

    fun provider(context: Context): Provider = runCatching {
        Provider.valueOf(prefs(context).getString("provider", Provider.OPENAI.name)!!)
    }.getOrDefault(Provider.OPENAI)

    fun baseUrl(context: Context): String {
        val stored = prefs(context).getString("base_url", null)
        return stored?.takeIf { it.isNotBlank() } ?: provider(context).baseUrl
    }

    fun sttModel(context: Context): String {
        val stored = prefs(context).getString("stt_model", null)
        val model = stored?.takeIf { it.isNotBlank() } ?: provider(context).sttModel
        // Normalize known non-existent slugs: OpenRouter has no
        // 'openai/whisper' — the real ids are openai/whisper-1 and
        // openai/whisper-large-v3. A wrong slug is a guaranteed 400.
        return when (model) {
            "openai/whisper" -> "openai/whisper-1"
            else -> model
        }
    }

    fun ttsModel(context: Context): String {
        val stored = prefs(context).getString("tts_model", null)
        return stored?.takeIf { it.isNotBlank() } ?: provider(context).ttsModel
    }

    fun ttsVoice(context: Context): String {
        val stored = prefs(context).getString("tts_voice", null)
        return stored?.takeIf { it.isNotBlank() } ?: provider(context).ttsVoice
    }

    fun setProvider(context: Context, provider: Provider) {
        prefs(context).edit().putString("provider", provider.name).apply()
    }

    fun setBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString("base_url", url.trim()).apply()
    }

    fun setSttModel(context: Context, model: String) {
        prefs(context).edit().putString("stt_model", model.trim()).apply()
    }

    fun setTtsModel(context: Context, model: String) {
        prefs(context).edit().putString("tts_model", model.trim()).apply()
    }

    fun setTtsVoice(context: Context, voice: String) {
        prefs(context).edit().putString("tts_voice", voice.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
