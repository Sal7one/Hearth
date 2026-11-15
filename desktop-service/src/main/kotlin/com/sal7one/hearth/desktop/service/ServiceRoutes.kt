package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.service.ServiceOperation
import com.sal7one.hearth.core.service.ServiceRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

/** A route and, when it cannot run right now, the actual reason. */
internal data class RouteView(val route: ServiceRoute, val unavailable: String?) {
    val ready: Boolean get() = unavailable == null
}

/**
 * Routes are what the owner saved in Hearth: the speech, translation and voice setups chosen
 * in the app become `transcribe`, `translate`, `speak` and `pipeline`. A caller can ask only
 * for the languages and voice the owner saved. Cloud stages are never offered, so a caller
 * cannot spend the owner's provider account.
 */
internal object SavedRoutes {
    fun from(saved: PipelineConfig, engines: List<EngineDescriptor>): List<RouteView> {
        fun local(selection: EngineSelection?, stage: PipelineStage): Pair<EngineSelection, EngineDescriptor>? {
            val engine = engines.singleOrNull { it.id == selection?.engineId && it.stage == stage } ?: return null
            return if (engine.kind == EngineKind.CLOUD) null else selection!! to engine
        }
        val speech = local(saved.speech, PipelineStage.STT)
        val translation = local(saved.translation, PipelineStage.TRANSLATION)
        val voice = local(saved.voice, PipelineStage.TTS)
        val voices = setOfNotNull(saved.voiceId.takeIf(String::isNotBlank))
        val routes = mutableListOf<RouteView>()
        // A saved setup that cannot form an operation (for example a voice with no declared language) offers no route for it.
        fun offer(stages: List<Pair<EngineSelection, EngineDescriptor>>, build: () -> ServiceRoute) {
            val route = runCatching(build).getOrNull() ?: return
            routes += RouteView(route, stages.firstNotNullOfOrNull { (selection, engine) -> unavailable(selection, engine) })
        }
        if (speech != null) offer(listOf(speech)) {
            ServiceRoute("transcribe", ServiceOperation.TRANSCRIBE, PipelineConfig(speech = speech.first, sourceLanguage = saved.sourceLanguage), setOf(saved.sourceLanguage))
        }
        if (translation != null) offer(listOf(translation)) {
            ServiceRoute("translate", ServiceOperation.TRANSLATE,
                PipelineConfig(translation = translation.first, sourceLanguage = saved.sourceLanguage, targetLanguage = saved.targetLanguage),
                setOf(saved.sourceLanguage), setOf(saved.targetLanguage))
        }
        if (voice != null) offer(listOf(voice)) {
            // The owner's voice speaks the translation when there is one, otherwise the source language.
            val language = if (saved.translation != null && saved.speakTranslatedText) saved.targetLanguage else saved.sourceLanguage
            ServiceRoute("speak", ServiceOperation.SPEAK, PipelineConfig(voice = voice.first, sourceLanguage = language, voiceId = saved.voiceId), setOf(language), voices = voices)
        }
        val everyStageLocal = (saved.translation == null || translation != null) && (saved.voice == null || voice != null)
        if (speech != null && everyStageLocal && (translation != null || voice != null)) offer(listOfNotNull(speech, translation, voice)) {
            ServiceRoute("pipeline", ServiceOperation.PIPELINE, saved.copy(speech = speech.first, translation = translation?.first, voice = voice?.first),
                setOf(saved.sourceLanguage), if (translation != null) setOf(saved.targetLanguage) else emptySet(), if (voice != null) voices else emptySet())
        }
        return routes
    }

    private fun unavailable(selection: EngineSelection, engine: EngineDescriptor): String? = when {
        !engine.available -> engine.unavailableReason ?: "${engine.name} is unavailable"
        engine.requiresModel && (selection.modelPath.isBlank() || !Files.exists(Path.of(selection.modelPath))) -> "The model for ${engine.name} is not installed"
        else -> null
    }
}

/** What this computer measured for a route: speed from its last completed job, memory from its last load. */
internal class ServiceMeasurements(private val file: Path) {
    data class Entry(val secondsPerWorkSecond: Double?, val ramBytes: Long?, val measured: Long)

    private val entries = HashMap<String, Entry>()

    init {
        runCatching {
            if (Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024) {
                for ((key, value) in Json.parseToJsonElement(Files.readString(file)).jsonObject) {
                    val entry = value.jsonObject
                    entries[key] = Entry(entry["secondsPerWorkSecond"]?.jsonPrimitive?.doubleOrNull, entry["ramBytes"]?.jsonPrimitive?.longOrNull, entry.getValue("measured").jsonPrimitive.long)
                }
            }
        }
    }

    /** Measurements belong to the exact engines, models and acceleration behind a route id. */
    fun key(route: ServiceRoute): String = route.id + "|" + listOfNotNull(route.config.speech, route.config.translation, route.config.voice)
        .joinToString("|") { "${it.engineId}:${it.modelId}:${it.options["acceleration"] ?: "cpu"}" }

    @Synchronized fun get(route: ServiceRoute): Entry? = entries[key(route)]

    @Synchronized fun record(route: ServiceRoute, secondsPerWorkSecond: Double?, ramBytes: Long?, now: Long) {
        val previous = entries[key(route)]
        entries[key(route)] = Entry(secondsPerWorkSecond?.takeIf { it.isFinite() && it > 0 } ?: previous?.secondsPerWorkSecond, ramBytes ?: previous?.ramBytes, now)
        val encoded = buildJsonObject {
            for ((key, entry) in entries) putJsonObject(key) {
                entry.secondsPerWorkSecond?.let { put("secondsPerWorkSecond", it) }; entry.ramBytes?.let { put("ramBytes", it) }; put("measured", entry.measured)
            }
        }
        runCatching { PrivateFiles.write(file, encoded.toString().toByteArray(Charsets.UTF_8)) }
    }
}
