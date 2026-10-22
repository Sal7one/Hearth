package com.sal7one.hearth.desktop.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One instance per application directory. Provider secrets are deliberately outside DataStore. */
class DesktopSettingsStore(val directory: Path) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Mutex()
    private val key = stringPreferencesKey("pipeline_v1")
    private val uiKey = stringPreferencesKey("ui_v1")
    private val store = PreferenceDataStoreFactory.createWithPath(scope = scope) {
        directory.resolve("settings.preferences_pb").toAbsolutePath().toString().toPath()
    }
    private val mutableConfig = MutableStateFlow(PipelineConfig())
    val config: StateFlow<PipelineConfig> = mutableConfig.asStateFlow()
    private val mutableUi = MutableStateFlow<Map<String, String>>(emptyMap())
    val ui: StateFlow<Map<String, String>> = mutableUi.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    init {
        Files.createDirectories(directory)
        scope.launch {
            try {
                store.data.collect { prefs ->
                    prefs[key]?.let { mutableConfig.value = SetupCodec.decode(it) }
                    prefs[uiKey]?.let { mutableUi.value = Json.parseToJsonElement(it).jsonObject.mapValues { value -> value.value.jsonPrimitive.content } }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = "Cannot read desktop settings: ${failure.message}" }
        }
    }
    suspend fun load(): PipelineConfig = updates.withLock {
        val preferences = store.data.first()
        preferences[key]?.let { mutableConfig.value = SetupCodec.decode(it) }
        preferences[uiKey]?.let { mutableUi.value = Json.parseToJsonElement(it).jsonObject.mapValues { value -> value.value.jsonPrimitive.content } }
        mutableConfig.value
    }
    suspend fun save(config: PipelineConfig) = updates.withLock {
        val encoded = SetupCodec.encode(config)
        store.edit { it[key] = encoded }
        mutableConfig.value = config.copy(
            speech = config.speech?.let { it.copy(options = it.options.toMap()) },
            translation = config.translation?.let { it.copy(options = it.options.toMap()) },
            voice = config.voice?.let { it.copy(options = it.options.toMap()) },
        )
    }
    suspend fun saveUi(values: Map<String, String>) = updates.withLock {
        require(values.size <= 64 && values.all { it.key.length <= 64 && it.value.length <= 4096 })
        // Screens submit independent patches. Merge inside the DataStore transaction,
        // using persisted values rather than a potentially delayed collector snapshot.
        var merged = emptyMap<String, String>()
        store.edit { preferences ->
            val previous = preferences[uiKey]?.let { encoded ->
                Json.parseToJsonElement(encoded).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }.orEmpty()
            merged = previous + values
            require(merged.size <= 64) { "At most 64 desktop UI preferences are allowed" }
            preferences[uiKey] = JsonObject(merged.mapValues { JsonPrimitive(it.value) }).toString()
        }
        mutableUi.value = merged
    }
    suspend fun exportSetup(path: Path) {
        val portable = load().let { it.copy(
            speech = it.speech?.portable(), translation = it.translation?.portable(), voice = it.voice?.portable(),
        ) }
        writeAtomic(path, SetupCodec.encode(portable))
    }
    suspend fun importSetup(path: Path) {
        require(Files.isRegularFile(path) && Files.size(path) <= 128 * 1024) { "Setup must be a JSON file smaller than 128 KiB" }
        val imported = SetupCodec.decode(Files.readString(path), portable = true)
        save(imported)
    }
    suspend fun closeAndJoin() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]?.join() }
    override fun close() { scope.cancel() }
    companion object {
        fun defaultDirectory(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = Path.of(System.getProperty("user.home"))
            return when {
                "win" in os -> Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString(), "Hearth")
                "mac" in os -> home.resolve("Library/Application Support/Hearth")
                else -> Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString(), "hearth")
            }
        }
        internal fun writeAtomic(path: Path, text: String) {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".hearth-", ".tmp")
            try {
                Files.writeString(temporary, text)
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}

private fun EngineSelection.portable() = copy(modelPath = "", credentialId = "", options = options.filterKeys { it in SetupCodec.allowedOptions || it.startsWith("sha256.") })

object SetupCodec {
    internal val allowedOptions = setOf("sha256", "threads", "acceleration", "voice", "speed", "steps", "temperature", "maxTokens", "profile", "backend", "family", "promptMode", "sources", "targets", "treeSha256", "windowMs")
    private val json = Json { prettyPrint = true }
    fun encode(config: PipelineConfig): String = json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("format", "hearth.desktop.setup"); put("schemaVersion", 1)
        put("sourceLanguage", config.sourceLanguage); put("targetLanguage", config.targetLanguage)
        put("voiceId", config.voiceId); put("speakTranslatedText", config.speakTranslatedText)
        config.speech?.let { put("speech", route(it)) }; config.translation?.let { put("translation", route(it)) }
        config.voice?.let { put("voice", route(it)) }
    }).also { decode(it) }
    private fun route(selection: EngineSelection) = buildJsonObject {
        require(selection.options.keys.all { it in allowedOptions || it.startsWith("sha256.") }) { "Unknown or unsafe engine option" }
        put("engineId", selection.engineId); put("modelId", selection.modelId); put("modelPath", selection.modelPath)
        put("endpoint", selection.endpoint); put("credentialId", selection.credentialId)
        put("options", JsonObject(selection.options.mapValues { JsonPrimitive(it.value) }))
    }
    fun decode(text: String, portable: Boolean = false): PipelineConfig {
        require(text.length <= 128 * 1024) { "Setup is too large" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == "hearth.desktop.setup" && root["schemaVersion"]?.jsonPrimitive?.int == 1) { "Unsupported Hearth setup format/version" }
        fun field(name: String, fallback: String) = root[name]?.jsonPrimitive?.content ?: fallback
        fun route(name: String): EngineSelection? {
            val value = root[name]?.jsonObject ?: return null
            fun get(key: String) = value[key]?.jsonPrimitive?.content ?: ""
            val options = value["options"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            require(options.size <= 24 && options.all { (it.key in allowedOptions || it.key.startsWith("sha256.")) && it.value.length <= 4096 }) { "Unsupported engine option" }
            options["windowMs"]?.let {
                val milliseconds = it.toIntOrNull()
                require(milliseconds != null && milliseconds in 1_000..30_000) { "Speech window must be 1000–30000 ms" }
            }
            require(get("engineId").matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid engine identity" }
            val endpoint = get("endpoint")
            require(endpoint.length <= 2048 && (endpoint.isEmpty() || java.net.URI(endpoint).let {
                it.userInfo == null && it.fragment == null && it.rawQuery == null && it.host != null &&
                    (it.scheme == "https" || (it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1", "::1")))
            })) { "Use HTTPS without embedded credentials, query or fragment; HTTP is allowed only on localhost" }
            return EngineSelection(get("engineId"), get("modelId"), if (portable) "" else get("modelPath"), endpoint,
                if (portable) "" else get("credentialId"), options)
        }
        val source = field("sourceLanguage", "auto"); val target = field("targetLanguage", "en")
        require(source.matches(Regex("[a-zA-Z][a-zA-Z0-9-]{1,31}")) && target.matches(Regex("[a-zA-Z][a-zA-Z0-9-]{1,31}"))) { "Invalid language code" }
        return PipelineConfig(route("speech"), route("translation"), route("voice"), source, target,
            field("voiceId", "").also { require(it.length <= 256) }, root["speakTranslatedText"]?.jsonPrimitive?.boolean ?: true)
    }
}
