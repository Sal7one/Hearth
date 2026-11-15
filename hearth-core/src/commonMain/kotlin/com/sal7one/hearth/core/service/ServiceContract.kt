package com.sal7one.hearth.core.service

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.PipelineConfig

/**
 * The one contract every Hearth service transport carries (docs/service-api.md): what a
 * caller may ask for, the bounds, and the errors. Loopback HTTP on desktop and a bound
 * service on Android marshal these types; neither adds or relaxes a rule.
 */
enum class ServiceOperation(val wire: String) {
    TRANSCRIBE("transcribe"), TRANSLATE("translate"), SPEAK("speak"), PIPELINE("pipeline");

    /** Audio is uploaded for these; the others carry bounded text in the request. */
    val takesAudio: Boolean get() = this == TRANSCRIBE || this == PIPELINE
}

enum class ServiceJobState(val wire: String, val terminal: Boolean) {
    AWAITING_INPUT("awaiting_input", false), QUEUED("queued", false), RUNNING("running", false),
    COMPLETED("completed", true), FAILED("failed", true), CANCELLED("cancelled", true),
}

enum class ServiceErrorCode(val wire: String, val httpStatus: Int) {
    UNAUTHORIZED("unauthorized", 401),
    INVALID_REQUEST("invalid_request", 400),
    UNKNOWN_ROUTE("unknown_route", 404),
    UNKNOWN_JOB("unknown_job", 404),
    ROUTE_NOT_READY("route_not_ready", 409),
    KEY_CONFLICT("key_conflict", 409),
    BUSY("busy", 503),
    NOT_RESIDENT("not_resident", 503),
    INPUT_TOO_LARGE("input_too_large", 413),
    INPUT_TOO_LONG("input_too_long", 422),
    UNSUPPORTED_MEDIA("unsupported_media", 415),
    WOULD_EXCEED_DEADLINE("would_exceed_deadline", 422),
    OUTPUT_TOO_LARGE("output_too_large", 507),
    ENGINE_FAILED("engine_failed", 500),
    CANCELLED("cancelled", 409),
}

/** A refusal or failure with its stable code. The message is the actual reason, never a placeholder. */
class ServiceException(val code: ServiceErrorCode, message: String) : Exception(message)

object ServiceLimits {
    const val PROTOCOL = 1
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    const val MAX_TEXT_CHARACTERS = 12_000
    const val MAX_AUDIO_SECONDS = 3_600
    /** One output file. Generated speech is stopped as soon as it would pass this. */
    const val MAX_OUTPUT_BYTES = 64L * 1024 * 1024
    const val MAX_RECORD_BYTES = 16 * 1024
    const val MAX_DEADLINE_SECONDS = 24 * 3_600
    /** Jobs the caller never forgets are purged this long after they finish. */
    const val JOB_LIFETIME_SECONDS = 3_600
    val KEY = Regex("[0-9a-f]{32}")
    val ROUTE_ID = Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")
    val LANGUAGE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,2}|auto")
}

/**
 * An owner-approved preset. [config] holds the engine selections and stays inside Hearth:
 * a caller names [id] and nothing else, so it can never choose an engine, a model or a path.
 */
data class ServiceRoute(
    val id: String,
    val operation: ServiceOperation,
    val config: PipelineConfig,
    val sourceLanguages: Set<String>,
    val targetLanguages: Set<String> = emptySet(),
    val voices: Set<String> = emptySet(),
    val maxAudioSeconds: Int = ServiceLimits.MAX_AUDIO_SECONDS,
    val maxTextCharacters: Int = ServiceLimits.MAX_TEXT_CHARACTERS,
) {
    init {
        require(id.matches(ServiceLimits.ROUTE_ID)) { "Invalid route id" }
        val stages = Triple(config.speech != null, config.translation != null, config.voice != null)
        require(when (operation) {
            ServiceOperation.TRANSCRIBE -> stages == Triple(true, false, false)
            ServiceOperation.TRANSLATE -> stages == Triple(false, true, false)
            ServiceOperation.SPEAK -> stages == Triple(false, false, true)
            ServiceOperation.PIPELINE -> stages.first && (stages.second || stages.third)
        }) { "Route $id does not configure exactly the stages of ${operation.wire}" }
        require(sourceLanguages.isNotEmpty() && (sourceLanguages + targetLanguages).all { it.matches(ServiceLimits.LANGUAGE) }) { "Route $id has no valid languages" }
        require(config.translation == null || targetLanguages.isNotEmpty()) { "Route $id translates but lists no target language" }
        require(operation != ServiceOperation.SPEAK || "auto" !in sourceLanguages) { "A voice needs a declared language" }
        require(maxAudioSeconds in 1..ServiceLimits.MAX_AUDIO_SECONDS && maxTextCharacters in 1..ServiceLimits.MAX_TEXT_CHARACTERS) { "Route $id exceeds Hearth's limits" }
    }
}

data class ServiceJobRequest(
    val route: String,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val voice: String? = null,
    val text: String? = null,
    val deadlineSeconds: Int? = null,
    val requireResident: Boolean = false,
) {
    /**
     * Identity for idempotent creation: the same key with the same fingerprint returns the
     * existing job, a different one is a conflict. Length-prefixed so no field can imitate another.
     */
    fun fingerprint(): String = listOf(route, sourceLanguage, targetLanguage, voice, text, deadlineSeconds?.toString(), requireResident.toString())
        .joinToString("") { field -> if (field == null) "-;" else "${field.length}:$field;" }
}

object ServiceContract {
    fun requireKey(key: String) {
        if (!key.matches(ServiceLimits.KEY)) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "A job key is 32 lower-case hexadecimal characters")
    }

    /**
     * Checks a request against its route and returns the configuration the job runs with.
     * Every value a caller may influence must be one the route lists.
     */
    fun resolve(request: ServiceJobRequest, route: ServiceRoute): PipelineConfig {
        fun refuse(message: String): Nothing = throw ServiceException(ServiceErrorCode.INVALID_REQUEST, message)
        if (request.route != route.id) refuse("Request names a different route")
        val text = request.text
        if (route.operation.takesAudio) {
            if (text != null) refuse("${route.operation.wire} takes uploaded audio, not text")
        } else {
            if (text.isNullOrBlank()) refuse("${route.operation.wire} needs text")
            if (text.length > route.maxTextCharacters) refuse("Text exceeds this route's ${route.maxTextCharacters} characters")
        }
        val source = request.sourceLanguage ?: route.config.sourceLanguage
        if (source !in route.sourceLanguages) refuse("Source language $source is not offered by route ${route.id}")
        val translates = route.config.translation != null
        val target = request.targetLanguage ?: route.config.targetLanguage
        if (translates) {
            if (target !in route.targetLanguages) refuse("Target language $target is not offered by route ${route.id}")
        } else if (request.targetLanguage != null) refuse("Route ${route.id} does not translate")
        val voice = request.voice ?: route.config.voiceId
        if (route.config.voice == null) {
            if (request.voice != null) refuse("Route ${route.id} has no voice")
        } else if (route.voices.isNotEmpty() && voice !in route.voices) refuse("Voice $voice is not offered by route ${route.id}")
        request.deadlineSeconds?.let { if (it !in 1..ServiceLimits.MAX_DEADLINE_SECONDS) refuse("deadlineSeconds must be 1..${ServiceLimits.MAX_DEADLINE_SECONDS}") }
        return route.config.copy(sourceLanguage = source, targetLanguage = if (translates) target else route.config.targetLanguage, voiceId = voice)
    }

    /** Refuses audio a route cannot take, before any model is loaded. */
    fun requireAudioLength(seconds: Double, route: ServiceRoute) {
        if (!(seconds > 0.0)) throw ServiceException(ServiceErrorCode.UNSUPPORTED_MEDIA, "The upload contains no audio")
        if (seconds > route.maxAudioSeconds) throw ServiceException(ServiceErrorCode.INPUT_TOO_LONG,
            "Audio is ${seconds.toLong()} s; route ${route.id} accepts at most ${route.maxAudioSeconds} s")
    }

    /** Whole seconds a job is expected to take, or null when this route has not been measured here. */
    fun estimateSeconds(workSeconds: Double, secondsPerWorkSecond: Double?): Int? =
        secondsPerWorkSecond?.takeIf { it > 0.0 && workSeconds > 0.0 }?.let { kotlin.math.ceil(workSeconds * it).toInt().coerceAtLeast(1) }

    fun requireWithinDeadline(estimateSeconds: Int?, deadlineSeconds: Int?) {
        if (estimateSeconds != null && deadlineSeconds != null && estimateSeconds > deadlineSeconds) {
            throw ServiceException(ServiceErrorCode.WOULD_EXCEED_DEADLINE, "Estimated $estimateSeconds s exceeds the $deadlineSeconds s deadline")
        }
    }

    /** WebVTT of final segments: the translation where one exists, otherwise the original. */
    fun webVtt(segments: List<CaptionSegment>): String = buildString {
        append("WEBVTT\n")
        for (segment in segments) {
            if (!segment.final) continue
            val text = (segment.translation ?: segment.original).lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")
            if (text.isEmpty()) continue
            val start = segment.startMs.coerceAtLeast(0)
            append('\n').append(timestamp(start)).append(" --> ").append(timestamp(maxOf(segment.endMs, start + 1))).append('\n')
            append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append('\n')
        }
    }

    private fun timestamp(milliseconds: Long): String {
        fun two(value: Long) = value.toString().padStart(2, '0')
        return "${two(milliseconds / 3_600_000)}:${two(milliseconds / 60_000 % 60)}:${two(milliseconds / 1_000 % 60)}.${(milliseconds % 1_000).toString().padStart(3, '0')}"
    }
}
