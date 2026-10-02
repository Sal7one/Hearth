package com.sal7one.transiber.byok

import android.content.Context

/** Separate voice preferences and encrypted, endpoint-scoped keys. No STT setting is written. */
internal object CloudVoiceStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("cloud_voice_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun config(context: Context): CloudVoiceConfig {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud voices are unavailable in the offline build" }
        val p = prefs(context)
        if (!p.contains("provider")) {
            // Snapshot a working legacy cloud voice once; subsequent STT changes cannot affect it.
            val legacyKey = synchronized(ApiKeyStore) {
                ApiKeyStore.getOpenAiKey(context).also { ApiKeyStore.lastFailure?.let { message -> error(message) } }
            }
            val initial = if (legacyKey.isNotBlank()) {
                val endpoint = CloudConfigStore.baseUrl(context)
                val provider = when(canonicalEndpoint(endpoint)) {
                    CloudVoiceProvider.OPENAI.endpoint -> CloudVoiceProvider.OPENAI
                    CloudVoiceProvider.OPENROUTER.endpoint -> CloudVoiceProvider.OPENROUTER
                    else -> CloudVoiceProvider.CUSTOM
                }
                val model = CloudConfigStore.ttsModel(context)
                val voice = CloudConfigStore.ttsVoice(context)
                // Old OpenRouter defaults sent an OpenAI-only voice to Google.
                val correctedVoice = if (provider == CloudVoiceProvider.OPENROUTER && model.startsWith("google/gemini-") && voice == "alloy") "Kore" else voice
                CloudVoiceConfig(provider, endpoint, model, correctedVoice)
            } else CloudVoiceConfig()
            save(context, initial, legacyKey, requireVoice = false)
        }
        val provider = CloudVoiceProvider.valueOf(checkNotNull(p.getString("provider", null)))
        return forProvider(context, provider)
    }

    fun forProvider(context: Context, provider: CloudVoiceProvider): CloudVoiceConfig {
        val p = prefs(context); val prefix = provider.name + "."
        return CloudVoiceConfig(provider,
            p.getString(prefix + "endpoint", provider.endpoint).orEmpty(),
            p.getString(prefix + "model", provider.model).orEmpty(),
            p.getString(prefix + "voice", provider.voice).orEmpty(),
            p.getString(prefix + "female", "").orEmpty(), p.getString(prefix + "male", "").orEmpty(),
            p.getFloat(prefix + "speed", 1f), p.getString(prefix + "instructions", "").orEmpty(), p.getBoolean(prefix + "defaultVoice", false))
    }

    @Synchronized
    fun save(context: Context, value: CloudVoiceConfig, replacementKey: String?, requireVoice: Boolean = true) {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud voices are unavailable in the offline build" }
        val config = value.validated(requireVoice)
        replacementKey?.let {
            check(ApiKeyStore.setTtsKey(context, config.credentialScope(), it)) { ApiKeyStore.lastFailure ?: "Could not save voice credential" }
        }
        val prefix = config.provider.name + "."
        check(prefs(context).edit().putString("provider", config.provider.name)
            .putString(prefix + "endpoint", config.endpoint).putString(prefix + "model", config.model)
            .putString(prefix + "voice", config.voice).putString(prefix + "female", config.femaleVoice)
            .putString(prefix + "male", config.maleVoice).putFloat(prefix + "speed", config.speed)
            .putString(prefix + "instructions", config.instructions).putBoolean(prefix + "defaultVoice", config.providerDefaultVoice).commit()) { "Could not save cloud voice settings" }
    }

    fun key(context: Context, config: CloudVoiceConfig): String {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud voices are unavailable in the offline build" }
        val key = ApiKeyStore.getTtsKey(context, config.credentialScope())
        check(key.isNotBlank()) { "Save an API key for ${config.provider.label} in Voices → Cloud" }
        return key
    }

    fun hasKey(context: Context, config: CloudVoiceConfig): Boolean =
        runCatching { key(context, config).isNotBlank() }.getOrDefault(false)

    fun removeKey(context: Context, config: CloudVoiceConfig) {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud voices are unavailable in the offline build" }
        check(ApiKeyStore.setTtsKey(context, config.credentialScope(), "")) { ApiKeyStore.lastFailure ?: "Could not remove voice credential" }
    }
}
