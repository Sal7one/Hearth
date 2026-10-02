package com.sal7one.transiber.byok

import com.sal7one.transiber.voice.VoiceGender
import com.sal7one.transiber.voice.VoiceGenderMapping
import java.net.URI
import java.security.MessageDigest

/** Voice configuration is independent of STT models, endpoints and credentials. */
internal enum class CloudVoiceProvider(val label: String, val endpoint: String, val model: String,
    val voice: String, val keyUrl: String, val docsUrl: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-tts", "coral",
        "https://platform.openai.com/api-keys", "https://developers.openai.com/api/docs/guides/text-to-speech"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-3.8-flash-lite-tts", "Kore",
        "https://openrouter.ai/settings/keys", "https://openrouter.ai/docs/guides/overview/multimodal/tts"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash-lite-tts", "Kore",
        "https://aistudio.google.com/apikey", "https://ai.google.dev/gemini-api/docs/speech-generation"),
    ELEVENLABS("ElevenLabs", "https://api.elevenlabs.io/v1", "eleven_flash_v2_5", "",
        "https://elevenlabs.io/app/settings/api-keys", "https://elevenlabs.io/docs/overview/capabilities/text-to-speech"),
    CUSTOM("OpenAI-compatible", "", "", "",
        "", "https://developers.openai.com/api/docs/guides/text-to-speech"),
}

internal data class CloudVoiceConfig(
    val provider: CloudVoiceProvider = CloudVoiceProvider.OPENAI,
    val endpoint: String = provider.endpoint,
    val model: String = provider.model,
    val voice: String = provider.voice,
    val femaleVoice: String = "",
    val maleVoice: String = "",
    val speed: Float = 1f,
    val instructions: String = "",
    val providerDefaultVoice: Boolean = false,
) {
    fun validated(requireVoice: Boolean = true): CloudVoiceConfig {
        val base = canonicalEndpoint(endpoint)
        require(model.isNotBlank() && model.length <= 200 && !model.any { it.isISOControl() }) { "Choose a TTS model" }
        require(!providerDefaultVoice || provider == CloudVoiceProvider.OPENROUTER) { "Provider default voice is supported only by OpenRouter" }
        require(!requireVoice || voice.isNotBlank() || providerDefaultVoice) { "Choose a voice or enter its provider voice ID" }
        require(listOf(voice, femaleVoice, maleVoice).all { it.length <= 200 && !it.any(Char::isISOControl) }) { "Invalid voice ID" }
        val range = if (provider == CloudVoiceProvider.ELEVENLABS) .7f..1.2f else .5f..2f
        require(speed.isFinite() && speed in range) { "Voice speed must be between ${range.start} and ${range.endInclusive}" }
        require(instructions.length <= 1000) { "Voice instructions exceed 1000 characters" }
        if (provider != CloudVoiceProvider.CUSTOM) {
            require(base == canonicalEndpoint(provider.endpoint)) { "This provider uses ${provider.endpoint}; use Custom for another endpoint" }
        }
        return copy(endpoint = base, model = model.trim(), voice = voice.trim(),
            femaleVoice = femaleVoice.trim(), maleVoice = maleVoice.trim(), instructions = instructions.trim())
    }

    fun selectedVoice(gender: VoiceGender): String = when {
        gender == VoiceGender.FEMALE && femaleVoice.isNotBlank() -> femaleVoice
        gender == VoiceGender.MALE && maleVoice.isNotBlank() -> maleVoice
        provider == CloudVoiceProvider.OPENAI || provider == CloudVoiceProvider.OPENROUTER && model.startsWith("openai/") ->
            VoiceGenderMapping.cloudVoice(voice, gender)
        else -> voice // Never send an OpenAI voice name to an unrelated provider.
    }

    fun supportsSpeed() = provider == CloudVoiceProvider.OPENAI || provider == CloudVoiceProvider.CUSTOM ||
        provider == CloudVoiceProvider.ELEVENLABS || provider == CloudVoiceProvider.OPENROUTER && model.startsWith("openai/")
    fun supportsInstructions() = provider == CloudVoiceProvider.GEMINI && usesInteractions() ||
        provider == CloudVoiceProvider.OPENAI && model.startsWith("gpt-4o-mini-tts")
    fun usesInteractions() = provider == CloudVoiceProvider.GEMINI && model.startsWith("gemini-3.8-")

    /** Endpoint-scoped keys cannot follow a Custom URL edit to another server. */
    fun credentialScope(): String = MessageDigest.getInstance("SHA-256")
        .digest("${provider.name}|${canonicalEndpoint(endpoint)}".toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun canonicalEndpoint(value: String): String {
    val uri = URI(value.trim().trimEnd('/'))
    val scheme = uri.scheme?.lowercase()
    val loopback = uri.host?.lowercase() in setOf("127.0.0.1", "localhost", "[::1]", "::1")
    require(scheme == "https" || scheme == "http" && loopback) { "Voice endpoint must use HTTPS" }
    require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid voice endpoint" }
    require(uri.port == -1 || uri.port in 1..65535) { "Invalid voice endpoint port" }
    return URI(scheme, null, uri.host.lowercase(), uri.port, uri.path.trimEnd('/'), null, null).toASCIIString()
}

internal object CloudVoiceCatalog {
    val openAiVoices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar")
    val geminiVoices = listOf("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat")
    fun voices(config: CloudVoiceConfig): List<String> = when {
        config.provider == CloudVoiceProvider.OPENAI && config.model in setOf("tts-1", "tts-1-hd") ->
            listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
        config.provider == CloudVoiceProvider.OPENAI || config.provider == CloudVoiceProvider.OPENROUTER && config.model.startsWith("openai/") -> openAiVoices
        config.provider == CloudVoiceProvider.GEMINI || config.provider == CloudVoiceProvider.OPENROUTER && config.model.startsWith("google/gemini-") -> geminiVoices
        else -> emptyList()
    }
    fun models(provider: CloudVoiceProvider): List<String> = when(provider) {
        CloudVoiceProvider.OPENAI -> listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd")
        CloudVoiceProvider.GEMINI -> listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-pro-preview-tts")
        CloudVoiceProvider.ELEVENLABS -> listOf("eleven_flash_v2_5", "eleven_multilingual_v2", "eleven_v3")
        CloudVoiceProvider.OPENROUTER -> listOf(provider.model)
        CloudVoiceProvider.CUSTOM -> emptyList()
    }
}
