package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.service.ServiceOperation
import com.sal7one.hearth.core.service.ServiceRoute
import com.sal7one.hearth.desktop.data.SetupCodec
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

/** A route, the named setup it comes from (null for the setup the app shows) and, when it cannot run right now, the actual reason. */
internal data class RouteView(val route: ServiceRoute, val unavailable: String?, val setup: String? = null) {
    val ready: Boolean get() = unavailable == null
}

/**
 * Routes are what the owner saved in Hearth: the speech, translation and voice setups chosen
 * in the app become `transcribe`, `translate`, `speak` and `pipeline`. A caller can ask only
 * for the languages and voice the owner saved. Cloud stages are never offered, so a caller
 * cannot spend the owner's provider account.
 */
internal object SavedRoutes {
    /** [setup] names a setup the owner saved for callers; its routes are `<setup>-transcribe` and so on. */
    fun from(saved: PipelineConfig, engines: List<EngineDescriptor>, setup: String? = null): List<RouteView> {
        fun id(operation: ServiceOperation) = if (setup == null) operation.wire else "$setup-${operation.wire}"
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
            routes += RouteView(route, stages.firstNotNullOfOrNull { (selection, engine) -> unavailable(selection, engine) }, setup)
        }
        if (speech != null) offer(listOf(speech)) {
            ServiceRoute(id(ServiceOperation.TRANSCRIBE), ServiceOperation.TRANSCRIBE, PipelineConfig(speech = speech.first, sourceLanguage = saved.sourceLanguage), setOf(saved.sourceLanguage))
        }
        if (translation != null) offer(listOf(translation)) {
            ServiceRoute(id(ServiceOperation.TRANSLATE), ServiceOperation.TRANSLATE,
                PipelineConfig(translation = translation.first, sourceLanguage = saved.sourceLanguage, targetLanguage = saved.targetLanguage),
                setOf(saved.sourceLanguage), setOf(saved.targetLanguage))
        }
        if (voice != null) offer(listOf(voice)) {
            // The owner's voice speaks the translation when there is one, otherwise the source language.
            val language = if (saved.translation != null && saved.speakTranslatedText) saved.targetLanguage else saved.sourceLanguage
            ServiceRoute(id(ServiceOperation.SPEAK), ServiceOperation.SPEAK, PipelineConfig(voice = voice.first, sourceLanguage = language, voiceId = saved.voiceId), setOf(language), voices = voices)
        }
        val everyStageLocal = (saved.translation == null || translation != null) && (saved.voice == null || voice != null)
        if (speech != null && everyStageLocal && (translation != null || voice != null)) offer(listOfNotNull(speech, translation, voice)) {
            ServiceRoute(id(ServiceOperation.PIPELINE), ServiceOperation.PIPELINE, saved.copy(speech = speech.first, translation = translation?.first, voice = voice?.first),
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

/**
 * Setups the owner saved for callers under a name, beside the one the app is showing. One
 * setup serves one language pair and one voice, so two directions are two setups. Each is the
 * app's own setup format in a file only the owner can read; a caller still names a route and
 * never sees an engine, a model or a path.
 */
internal object ServiceSetups {
    /** Short enough that `<name>-transcribe` is still a route id. */
    val NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?")
    const val MAXIMUM = 16
    private const val MAX_BYTES = 128L * 1024

    private fun file(directory: Path, name: String): Path {
        require(name.matches(NAME)) { "A setup name is 1 to 40 lower-case letters, digits and hyphens" }
        return directory.resolve("$name.json")
    }

    fun save(directory: Path, name: String, config: PipelineConfig): Path {
        val target = file(directory, name)
        require(config.speech != null || config.translation != null || config.voice != null) { "The setup saved in Hearth has no speech, translation or voice to offer" }
        PrivateFiles.directory(directory)
        require(Files.exists(target) || names(directory).size < MAXIMUM) { "At most $MAXIMUM named setups are kept" }
        PrivateFiles.write(target, SetupCodec.encode(config).toByteArray(Charsets.UTF_8))
        return target
    }

    fun remove(directory: Path, name: String): Boolean = Files.deleteIfExists(file(directory, name))

    private fun names(directory: Path): List<String> = if (!Files.isDirectory(directory)) emptyList() else
        Files.newDirectoryStream(directory, "*.json").use { files -> files.map { it.fileName.toString().removeSuffix(".json") }.filter { it.matches(NAME) }.sorted() }

    /** Every named setup in name order. One that cannot be read is a failure with its reason, and does not hide the others. */
    fun load(directory: Path): Map<String, Result<PipelineConfig>> = names(directory).take(MAXIMUM).associateWith { name ->
        runCatching {
            val path = file(directory, name)
            require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= MAX_BYTES) { "Setup $name is not a regular file within its size bound" }
            SetupCodec.decode(Files.readString(path))
        }
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
