package com.sal7one.transiber.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.OcrModels
import com.sal7one.transiber.voice.VoiceCatalog
import com.sal7one.transiber.voice.VoiceModels
import com.sal7one.transiber.voice.VoiceSettings
import com.sal7one.transiber.voice.VoiceGender
import com.sal7one.transiber.voice.VoiceGenderMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private copy availability and original document access are separate facts. */
enum class ModelSourceAccess { NOT_RECORDED, GRANT_RETAINED_NOT_PROBED, GRANT_MISSING }
data class ModelBindingCheck(val modelId: String, val paths: List<String>, val available: Boolean,
    val error: String? = null, val changedSinceImport: Boolean = false, val sourceUri: String? = null,
    val sourceAccess: ModelSourceAccess = ModelSourceAccess.NOT_RECORDED)

data class ModelSourceCheck(val readable: Boolean, val bytes: Long? = null, val error: String? = null)
private data class SourceBinding(val id: String, val sourceUri: String?, val paths: List<BoundModelPath>, val error: String? = null)

/** Local bindings never travel in portable setup JSON. Imports remain immutable private copies. */
object ModelSourceBindings {
    private const val STORE = "model-source-bindings"
    private val mutableChecks = MutableStateFlow<List<ModelBindingCheck>>(emptyList())
    val checks = mutableChecks.asStateFlow()
    private val bindingMutex = Mutex()

    /** Call only after the ordinary pinned/TOFU import has succeeded. */
    suspend fun recordImported(context: Context, modelId: String, source: Uri, artifactPaths: List<File>) = withContext(Dispatchers.IO) {
        bindingMutex.withLock {
            require(source.scheme == "content") { "Model source must be a selected document" }
            require(source.toString().length <= 8192) { "Model source URI is too long" }
            require(artifactPaths.isNotEmpty() && artifactPaths.size <= 32) { "A model binding requires 1..32 private paths" }
            // Persist the grant when the provider supports it. The verified private
            // copy remains usable when an original source cannot retain a grant.
            try { context.contentResolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Report GRANT_MISSING independently of private copy availability. */ }
            val binding = SourceBinding(modelId, source.toString(), artifactPaths.map { ModelPathSnapshots.capture(context.filesDir, it) })
            save(context, binding)
            recheckLocked(context)
        }
    }
    /** Launch work is stat/access checks on private files plus the local grant table, never remote provider I/O or GB hashing. */
    suspend fun verifyAtLaunch(context: Context): List<ModelBindingCheck> = withContext(Dispatchers.IO) {
        recheck(context)
    }
    suspend fun recheck(context: Context): List<ModelBindingCheck> = withContext(Dispatchers.IO) {
        bindingMutex.withLock { recheckLocked(context) }
    }
    private suspend fun recheckLocked(context: Context): List<ModelBindingCheck> {
        bootstrap(context)
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val results = read(context).map { binding ->
            val states = binding.paths.map { ModelPathSnapshots.check(context.filesDir, it) }
            ModelBindingCheck(binding.id, binding.paths.map { it.path }, states.isNotEmpty() && states.all { it.available },
                binding.error ?: states.firstOrNull { !it.available }?.error, states.any { it.changed }, binding.sourceUri,
                when (binding.sourceUri) { null -> ModelSourceAccess.NOT_RECORDED; in grants -> ModelSourceAccess.GRANT_RETAINED_NOT_PROBED; else -> ModelSourceAccess.GRANT_MISSING })
        }.toMutableList()
        val config = CaptionConfigStore.config(context).first()
        val registered = ModelRegistry.getInstance(context).registeredModels.value
        if (config.engine in setOf(CaptionEngineChoice.WHISPER, CaptionEngineChoice.VOSK) && config.modelId.isNotBlank() &&
            registered.none { it.id == config.modelId } && results.none { it.modelId == config.modelId })
            results += ModelBindingCheck(config.modelId, emptyList(), false, "Selected speech model is no longer registered. Import or select the model again.")
        val selectedTranslations = setOf(config.localTranslationModelId,
            UserPreferences.store(context, "conversation-translators").getString("local-model", null),
            UserPreferences.store(context, "camera-translate").getString("local-model", null))
        selectedTranslations.filterNotNull().forEach { id ->
            val pair = MarianPackage.find(id)
            val route = MarianCascade.find(id)
            val parts = pair?.let(::listOf) ?: route?.let { listOf(it.first, it.second) }.orEmpty()
            if (parts.isNotEmpty()) {
                val models = parts.map { MarianPackage.installed(context, it) }
                val partChecks = models.map { model -> model?.let { value -> results.firstOrNull { it.modelId == value.id } } }
                val available = partChecks.all { it?.available == true }
                results += ModelBindingCheck(id, models.filterNotNull().map { it.path }, available,
                    if (available) null else "Selected translation route is missing a model or has an inaccessible path. Download or import its required models.")
            }
        }
        return results.sortedBy { it.modelId }.also { mutableChecks.value = it }
    }
    /** Explicit Check source action; a retained grant alone never claims that an original file exists. */
    suspend fun checkOriginalSource(context: Context, modelId: String): ModelSourceCheck = withContext(Dispatchers.IO) {
        val source = read(context).firstOrNull { it.id == modelId }?.sourceUri
            ?: return@withContext ModelSourceCheck(false, error = "No original source was recorded. Import or locate the model again.")
        try {
            val uri = Uri.parse(source)
            if (DocumentsContract.isTreeUri(uri)) {
                val document = DocumentFile.fromTreeUri(context, uri)
                return@withContext if (document?.exists() == true && document.isDirectory && document.canRead()) ModelSourceCheck(true)
                    else ModelSourceCheck(false, error = "The original model folder is unavailable")
            }
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                ModelSourceCheck(true, it.length.takeIf { bytes -> bytes >= 0 })
            } ?: ModelSourceCheck(false, error = "The original source is unavailable")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { ModelSourceCheck(false, error = error.message ?: error.toString()) }
    }
    suspend fun forget(context: Context, modelId: String) = withContext(Dispatchers.IO) {
        bindingMutex.withLock {
            check(UserPreferences.store(context, STORE).edit().remove(modelId).commit()) { "Could not remove model source binding" }
            mutableChecks.value = mutableChecks.value.filterNot { it.modelId == modelId }
        }
    }
    private suspend fun bootstrap(context: Context) {
        val existing = read(context).associateBy { it.id }.toMutableMap()
        val additions = mutableListOf<SourceBinding>()
        fun add(id: String, path: File, bytes: Long? = null, directory: Boolean = path.isDirectory) {
            val previous = existing[id]
            if (previous != null && previous.paths.all { it.files.isNotEmpty() }) return
            val captured = try { ModelPathSnapshots.capture(context.filesDir, path, bytes, directory) }
            catch (error: Exception) { BoundModelPath(path.absolutePath, directory, bytes ?: 0, emptyList(), error.message ?: error.toString()) }
            SourceBinding(id, previous?.sourceUri, listOf(captured)).also { additions += it; existing[id] = it }
        }
        ModelRegistry.getInstance(context).registeredModels.value.filter { File(it.path).isAbsolute }.forEach {
            add(it.id, File(it.path), it.sizeBytes.takeIf { size -> size >= 0 }, it.isDirectory)
        }
        val speechRoot = File(context.filesDir, "speech-models")
        speechRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("speech-[0-9a-f-]{36}")) }.forEach { add(it.name, it, directory = true) }
        val config = CaptionConfigStore.config(context).first()
        if (config.modelId.matches(Regex("speech-[0-9a-f-]{36}"))) add(config.modelId, File(speechRoot, config.modelId), directory = true)
        val selectedTranslations = setOf(config.localTranslationModelId,
            UserPreferences.store(context, "conversation-translators").getString("local-model", null),
            UserPreferences.store(context, "camera-translate").getString("local-model", null))
        TranslationCatalog.models.forEach { spec ->
            val file = File(context.filesDir, "translation-models/${spec.id}.gguf")
            if (file.exists() || spec.id in selectedTranslations) add(spec.id, file, spec.bytes, directory = false)
        }
        val camera = UserPreferences.store(context, "camera-translate")
        val selectedOcr = if (camera.contains("profile")) OcrCatalog.profiles.firstOrNull { it.id == camera.getString("profile", null) } else null
        val ocr = OcrModels(File(context.filesDir, "ocr-models"))
        OcrCatalog.assets.forEach { asset ->
            val file = ocr.file(asset)
            if (file.exists() || selectedOcr?.assets?.any { it.id == asset.id } == true) add(asset.id, file, asset.bytes, directory = false)
        }
        val voice = VoiceModels(File(context.filesDir, "voice-models"))
        val choice = VoiceSettings.choice(context)
        val selectedVoice = VoiceGenderMapping.supertonicVoice(choice.voice,
            runCatching { VoiceGender.valueOf(config.speakerGender.name) }.getOrDefault(VoiceGender.ANY))
        val requiredVoice = if (choice.backend == "supertonic") VoiceCatalog.core + VoiceCatalog.assets.filter { it.filename == "$selectedVoice.json" } else emptyList()
        VoiceCatalog.assets.forEach { asset ->
            val file = voice.file(asset)
            if (file.exists() || asset in requiredVoice) add(asset.id, file, asset.bytes, directory = false)
        }
        if (additions.isNotEmpty()) {
            val editor = UserPreferences.store(context, STORE).edit()
            additions.forEach { editor.putString(it.id, encode(it).toString()) }
            check(editor.commit()) { "Could not save model path bindings" }
        }
    }
    private fun save(context: Context, binding: SourceBinding) {
        require(binding.id.isNotBlank() && binding.id.length <= 512 && '\u0000' !in binding.id) { "Invalid model binding id" }
        check(UserPreferences.store(context, STORE).edit().putString(binding.id, encode(binding).toString()).commit()) { "Could not save model source binding" }
    }
    private fun read(context: Context): List<SourceBinding> = UserPreferences.store(context, STORE).all.map { (id, value) -> try {
        val raw = value as? String ?: error("Invalid model binding: $id")
        require(raw.length <= 4 * 1024 * 1024) { "Model binding is too large: $id" }
        val json = JSONObject(raw)
        require(json.getInt("version") == 1) { "Unsupported model binding: $id" }
        val paths = json.getJSONArray("paths")
        require(paths.length() in 1..32) { "Invalid model binding path count: $id" }
        SourceBinding(id, json.optString("sourceUri").takeIf { it.isNotBlank() }, (0 until paths.length()).map { i ->
            val path = paths.getJSONObject(i); val files = path.getJSONArray("files")
            require(files.length() <= ModelPathSnapshots.MAX_FILES) { "Model binding has too many files: $id" }
            BoundModelPath(path.getString("path"), path.getBoolean("directory"), path.getLong("bytes"), (0 until files.length()).map { j ->
                val file = files.getJSONObject(j)
                BoundModelFile(file.getString("relative"), file.getLong("bytes"), file.getLong("modified"))
            }, path.optString("error").takeIf { it.isNotBlank() })
        })
    } catch (error: Exception) {
        SourceBinding(id, null, emptyList(), "Saved model binding is invalid: ${error.message ?: error.toString()}. Import or locate this model again.")
    }
    }
    private fun encode(binding: SourceBinding) = JSONObject().put("version", 1).put("sourceUri", binding.sourceUri.orEmpty())
        .put("paths", JSONArray(binding.paths.map { path -> JSONObject().put("path", path.path).put("directory", path.directory).put("bytes", path.bytes)
            .put("error", path.error.orEmpty())
            .put("files", JSONArray(path.files.map { JSONObject().put("relative", it.relative).put("bytes", it.bytes).put("modified", it.modified) })) }))
}
