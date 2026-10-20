package com.sal7one.hearth.integration

import com.sal7one.hearth.core.*
import kotlinx.serialization.json.*

internal object IntegrationJson {
    val codec = Json { isLenient = false; ignoreUnknownKeys = false }
    private val terminal = setOf(PipelineStatus.COMPLETED, PipelineStatus.FAILED, PipelineStatus.CANCELLED)

    fun snapshot(snapshot: PipelineSnapshot, owned: Boolean): JsonObject {
        require(snapshot.segments.takeLast(256).sumOf { it.original.length.toLong() + (it.translation?.length ?: 0) } <= 200_000) {
            "Transcript exceeds the bounded integration view; inspect the session in Hearth"
        }
        require(snapshot.audio.take(32).sumOf { it.text.length.toLong() } <= 32_000) { "Speech output metadata exceeds the integration view" }
        return buildJsonObject {
        put("id", snapshot.id)
        put("status", snapshot.status.name.lowercase())
        snapshot.stage?.let { put("stage", it.name.lowercase()) }
        put("terminal", snapshot.status in terminal)
        put("ownedByIntegration", owned)
        snapshot.error?.let { put("error", redact(it)) }
        put("segmentsTruncated", snapshot.segments.size > 256)
        put("totalSegments", snapshot.segments.size)
        putJsonArray("segments") {
            snapshot.segments.takeLast(256).forEach { segment -> add(buildJsonObject {
                put("id", segment.id); put("original", segment.original)
                segment.translation?.let { put("translation", it) }
                put("final", segment.final); put("startMs", segment.startMs); put("endMs", segment.endMs)
            }) }
        }
        // A local output path is never an external HTTP response field.
        putJsonArray("audio") { snapshot.audio.take(32).forEachIndexed { index, audio -> add(buildJsonObject {
            put("index", index); put("mimeType", audio.mimeType); put("text", audio.text)
            put("url", "/v1/sessions/${snapshot.id}/audio/$index")
        }) } }
        }
    }

    fun capabilities(engines: List<EngineDescriptor>): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        putJsonArray("inputs") { add("text"); add("wav-pcm16-16000-mono"); add("pcm-s16le-16000-mono"); add("native-media-upload") }
        put("maxTextCharacters", 12_000); put("maxAudioSeconds", IntegrationAudio.MAX_SECONDS)
        put("maxUploadBytes", IntegrationAudio.MAX_UPLOAD_BYTES); put("maxActiveSessions", 4)
        put("maxPcmFrameBytes", 6_400); put("pcmQueueCapacity", 8); put("livePcmInput", true)
        put("maxMediaUploadBytes", IntegrationMedia.MAX_BYTES); put("mediaUploadRequiresNativeDecoder", true)
        putJsonArray("mediaUploadContentTypes") { IntegrationMedia.extensions.keys.forEach { add(it) } }
        putJsonArray("engines") { engines.forEach { engine -> add(buildJsonObject {
            put("id", engine.id); put("name", engine.name); put("stage", engine.stage.name.lowercase())
            put("kind", engine.kind.name.lowercase()); put("available", engine.available)
            engine.unavailableReason?.let { put("unavailableReason", redact(it)) }
            put("requiresModel", engine.requiresModel); put("requiresCredential", engine.requiresCredential)
            put("streaming", engine.streaming)
            putJsonArray("languages") { engine.languages.sorted().forEach { add(it) } }
            putJsonArray("acceleration") { engine.acceleration.sorted().forEach { add(it) } }
        }) } }
    }

    fun overrides(base: PipelineConfig, fields: Map<String, String>): PipelineConfig {
        require(fields.keys.all { it in setOf("sourceLanguage", "targetLanguage", "translationEnabled", "voiceEnabled") }) {
            "Only sourceLanguage, targetLanguage, translationEnabled and voiceEnabled may override saved routes"
        }
        fun language(key: String, fallback: String): String = fields[key]?.also {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,34}"))) { "Invalid $key" }
        } ?: fallback
        fun enabled(key: String): Boolean? = fields[key]?.let {
            require(it in setOf("true", "false")) { "$key must be true or false" }; it == "true"
        }
        val translate = enabled("translationEnabled")
        val voice = enabled("voiceEnabled")
        check(translate != true || base.translation != null) { "Configure a translation engine in Hearth before enabling translation" }
        check(voice != true || base.voice != null) { "Configure a voice engine in Hearth before enabling speech output" }
        return base.copy(sourceLanguage = language("sourceLanguage", base.sourceLanguage),
            targetLanguage = language("targetLanguage", base.targetLanguage),
            translation = if (translate == false) null else base.translation,
            voice = if (voice == false) null else base.voice)
    }

    fun redact(message: String): String = message.take(8_192)
        .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*"), "$1[redacted]")
        .replace(Regex("(?i)([?&](?:api[_-]?key|token|key|secret|password)=)[^&\\s]+"), "$1[redacted]")
        .replace(Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[redacted]@")
}
