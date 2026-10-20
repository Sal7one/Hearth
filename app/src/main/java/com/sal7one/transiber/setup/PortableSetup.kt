package com.sal7one.transiber.setup

import com.sal7one.transiber.caption.CaptionSpeakerChoice
import com.sal7one.transiber.caption.SpeakerGender
import org.json.JSONObject
import org.json.JSONTokener

/** A data-only recipe. Model bytes, credentials, endpoints and local paths never travel with it. */
internal data class PortableModelRef(
    val family: String,
    val catalogId: String? = null,
    val profile: String? = null,
    val sha256: String? = null,
    val digestKind: String? = null,
) {
    init {
        require(identifier(family)) { "Invalid model family" }
        require(profile == null || identifier(profile)) { "Invalid speech profile" }
        if (catalogId != null) {
            require(identifier(catalogId) && sha256 == null && digestKind == null) { "A catalog reference cannot carry a custom digest" }
        } else {
            require(sha256?.matches(Regex("[a-f0-9]{64}")) == true && digestKind in setOf("file", "tree")) {
                "Custom models require their SHA-256 file or tree digest"
            }
        }
    }
}
internal data class PortableSpeechStage(val model: PortableModelRef, val sourceLanguage: String = "auto") {
    init { require(language(sourceLanguage)) { "Invalid spoken language" } }
}
internal data class PortableTranslationStage(
    val modelId: String, val sourceLanguage: String, val targetLanguage: String, val enabled: Boolean = true,
) {
    init {
        require(identifier(modelId)) { "Invalid translation model reference" }
        require(language(sourceLanguage) && language(targetLanguage) && targetLanguage != "auto") { "Invalid translation language" }
    }
}
internal data class PortableVoiceStage(
    val enabled: Boolean = false,
    val choice: CaptionSpeakerChoice = CaptionSpeakerChoice.SYSTEM,
    val gender: SpeakerGender = SpeakerGender.ANY,
    val volume: Int = 100,
    val voiceId: String? = null,
    val family: String? = null,
) {
    init {
        require(family == null || family in setOf("supertonic", "magpie")) { "Unsupported local voice family" }
        require(volume in 0..100) { "Voice volume must be 0–100" }
        require(voiceId == null || voiceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid voice reference" }
    }
}
internal data class PortableSetup(
    val name: String,
    val speech: PortableSpeechStage? = null,
    val translation: PortableTranslationStage? = null,
    val voice: PortableVoiceStage? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "Setup name must contain 1–80 visible characters" }
        require(speech != null || translation != null || voice != null) { "A setup requires at least one stage" }
    }
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", 1); put("name", name)
        speech?.let { stage -> put("speech", JSONObject().apply {
            put("model", JSONObject().apply {
                put("family", stage.model.family)
                stage.model.catalogId?.let { put("catalogId", it) }
                stage.model.profile?.let { put("profile", it) }
                stage.model.sha256?.let { put("sha256", it) }
                stage.model.digestKind?.let { put("digestKind", it) }
            }); put("sourceLanguage", stage.sourceLanguage)
        }) }
        translation?.let { stage -> put("translation", JSONObject().put("modelId", stage.modelId)
            .put("sourceLanguage", stage.sourceLanguage).put("targetLanguage", stage.targetLanguage).put("enabled", stage.enabled)) }
        voice?.let { stage -> put("voice", JSONObject().put("enabled", stage.enabled).put("choice", stage.choice.name)
            .put("gender", stage.gender.name).put("volume", stage.volume).apply { stage.voiceId?.let { put("voiceId", it) }; stage.family?.let { put("family", it) } }) }
    }.toString(2)

    companion object {
        const val MAX_BYTES = 16_384
        fun read(input: java.io.InputStream): PortableSetup {
            val buffer = ByteArray(MAX_BYTES + 1); var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                if (n == 0) {
                    val one = input.read(); if (one < 0) break
                    buffer[count++] = one.toByte()
                } else count += n
            }
            require(count <= MAX_BYTES) { "Setup file exceeds 16 KiB" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return parse(decoder.decode(java.nio.ByteBuffer.wrap(buffer, 0, count)).toString())
        }
        fun parse(raw: String): PortableSetup {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Setup file exceeds 16 KiB" }
            // These documents have a fixed shallow shape; bound parser recursion before JSONObject sees untrusted input.
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { c ->
                if (escaped) escaped = false
                else if (quoted && c == '\\') escaped = true
                else if (c == '"') quoted = !quoted
                else if (!quoted && c in "{[") { depth++; require(depth <= 5) { "Setup nesting exceeds the supported schema" } }
                else if (!quoted && c in "}]") depth--
            }
            val tokener = JSONTokener(raw)
            val root = tokener.nextValue() as? JSONObject ?: error("Setup must be a JSON object")
            require(tokener.nextClean() == '\u0000') { "Unexpected content after the setup object" }
            root.checked(setOf("schemaVersion", "name", "speech", "translation", "voice"))
            require(root.integer("schemaVersion") == 1) { "Unsupported setup schema version" }
            val speech = root.optionalObject("speech")?.checked(setOf("model", "sourceLanguage"))?.let { stage ->
                val model = stage.getJSONObject("model").checked(setOf("family", "catalogId", "profile", "sha256", "digestKind"))
                PortableSpeechStage(PortableModelRef(model.string("family"), model.optionalString("catalogId"),
                    model.optionalString("profile"), model.optionalString("sha256"), model.optionalString("digestKind")), stage.string("sourceLanguage"))
            }
            val translation = root.optionalObject("translation")?.checked(setOf("modelId", "sourceLanguage", "targetLanguage", "enabled"))?.let {
                PortableTranslationStage(it.string("modelId"), it.string("sourceLanguage"), it.string("targetLanguage"), it.boolean("enabled"))
            }
            val voice = root.optionalObject("voice")?.checked(setOf("enabled", "choice", "gender", "volume", "voiceId", "family"))?.let {
                PortableVoiceStage(it.boolean("enabled"), CaptionSpeakerChoice.valueOf(it.string("choice")),
                    SpeakerGender.valueOf(it.string("gender")), it.integer("volume"), it.optionalString("voiceId"), it.optionalString("family"))
            }
            return PortableSetup(root.string("name"), speech, translation, voice)
        }
    }
}
private fun identifier(value: String) = value.matches(Regex("[a-z0-9][a-z0-9._-]{0,119}"))
private fun language(value: String) = value.matches(Regex("[A-Za-z]{2,8}([_-][A-Za-z0-9]{2,8})*")) && value.length <= 40
private fun JSONObject.checked(allowed: Set<String>): JSONObject = apply {
    require(keys().asSequence().all { it in allowed }) { "Unknown setup field; only the declared speech, translation and voice settings are supported" }
}
private fun JSONObject.string(key: String): String = get(key).let { require(it is String) { "$key must be text" }; it }
private fun JSONObject.optionalString(key: String): String? = if (has(key)) string(key) else null
private fun JSONObject.optionalObject(key: String): JSONObject? = if (has(key)) getJSONObject(key) else null
private fun JSONObject.boolean(key: String): Boolean = get(key).let { require(it is Boolean) { "$key must be true or false" }; it }
private fun JSONObject.integer(key: String): Int = get(key).let {
    require(it is Number && it.toDouble().isFinite() && it.toDouble() == it.toInt().toDouble()) { "$key must be an integer" }; it.toInt()
}
