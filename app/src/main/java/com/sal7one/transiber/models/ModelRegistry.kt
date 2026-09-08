package com.sal7one.transiber.models

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.sal7one.common_jni.model.FileModelProvider
import com.sal7one.common_jni.model.ModelDigest
import com.sal7one.common_jni.model.ModelDigestAlgorithm
import com.sal7one.common_jni.model.ModelDigestKind
import com.sal7one.common_jni.model.ModelDigestTrust
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.model.ModelIntegrityException
import com.sal7one.common_jni.model.ModelMetadata
import com.sal7one.common_jni.model.ModelProvider
import com.sal7one.common_jni.model.ModelType
import com.sal7one.common_jni.model.StagedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Central registry for all models in the app.
 * 
 * This handles:
 * - Tracking models from assets, internal storage, and external imports
 * - Persisting model registrations across app restarts
 * - Providing ModelProvider instances for each registered model
 * - Avoiding duplicates based on file hash or path
 * 
 * Usage:
 * ```kotlin
 * // Get singleton instance
 * val registry = ModelRegistry.getInstance(context)
 * 
 * // Get all Whisper models
 * val whisperModels = registry.getModelsForEngine(ModelEngineType.WHISPER)
 * 
 * // Get a ModelProvider for a specific model
 * val provider = registry.getProvider(modelId)
 * ```
 */
class ModelRegistry private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelRegistry"
        private const val PREFS_NAME = "model_registry"
        private const val KEY_REGISTERED_MODELS = "registered_models"
        
        @Volatile
        private var instance: ModelRegistry? = null
        
        fun getInstance(context: Context): ModelRegistry {
            return instance ?: synchronized(this) {
                instance ?: ModelRegistry(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
    
    private val _registeredModels = MutableStateFlow<List<RegisteredModel>>(emptyList())
    val registeredModels: StateFlow<List<RegisteredModel>> = _registeredModels.asStateFlow()
    
    init {
        loadRegisteredModels()
    }
    
    // =============================================================================
    // Data Classes
    // =============================================================================
    
    /**
     * Represents a model registered in the app.
     */
    data class RegisteredModel(
        val id: String,                    // Unique ID (generated or from assets)
        val name: String,                  // Display name
        val engineType: ModelEngineType,   // WHISPER, VOSK, speech ONNX, translation
        val source: ModelSource,           // Where the model came from
        val path: String,                  // Absolute path to model file/directory
        val sizeBytes: Long,               // Size in bytes
        val isDirectory: Boolean,          // True for Vosk models
        val isValid: Boolean,              // Validation status
        val addedTimestamp: Long,          // When it was registered
        val digest: ModelDigest? = null,   // PINNED or explicitly TOFU
        val metadata: Map<String, String> = emptyMap() // Additional metadata
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("engineType", engineType.name)
            put("source", source.name)
            put("path", path)
            put("sizeBytes", sizeBytes)
            put("isDirectory", isDirectory)
            put("isValid", isValid)
            put("addedTimestamp", addedTimestamp)
            digest?.let { value ->
                put("digest", JSONObject().apply {
                    put("algorithm", value.algorithm.name)
                    put("kind", value.kind.name)
                    put("hex", value.hex)
                    put("trust", value.trust.name)
                })
            }
            put("metadata", JSONObject(metadata))
        }
        
        companion object {
            fun fromJson(json: JSONObject): RegisteredModel? {
                return try {
                    val digest = json.optJSONObject("digest")?.let { value ->
                        ModelDigest(
                            algorithm = ModelDigestAlgorithm.valueOf(value.getString("algorithm")),
                            kind = ModelDigestKind.valueOf(value.getString("kind")),
                            hex = value.getString("hex"),
                            trust = ModelDigestTrust.valueOf(value.getString("trust"))
                        )
                    }
                    val source = ModelSource.valueOf(json.getString("source"))
                    RegisteredModel(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        engineType = ModelEngineType.valueOf(json.getString("engineType")),
                        source = source,
                        path = json.getString("path"),
                        sizeBytes = json.getLong("sizeBytes"),
                        isDirectory = json.getBoolean("isDirectory"),
                        isValid = json.getBoolean("isValid") &&
                            (source == ModelSource.ASSET || digest != null),
                        addedTimestamp = json.getLong("addedTimestamp"),
                        digest = digest,
                        metadata = json.optJSONObject("metadata")?.let { meta ->
                            meta.keys().asSequence().associateWith { meta.getString(it) }
                        } ?: emptyMap()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse model JSON: ${e.message}")
                    null
                }
            }
        }
    }
    
    enum class ModelSource {
        ASSET,          // Bundled in APK assets
        INTERNAL,       // Copied to app's internal storage
        EXTERNAL,       // Referenced from external storage (needs permission)
        DOWNLOADED      // Downloaded from network
    }
    
    // =============================================================================
    // Registration
    // =============================================================================
    
    /**
     * Register a model from a file path.
     * 
     * @param path Absolute path to model file or directory
     * @param copyToInternal If true, copies the model to internal storage
     * @return RegisteredModel if successful, null otherwise
     */
    suspend fun registerModel(
        path: String,
        copyToInternal: Boolean = true,
        expectedSha256: String? = null
    ): RegisteredModel? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found: $path")
            return@withContext null
        }
        
        // The compatibility flag no longer permits external linking. Any path
        // outside this app-private model root is copied into it atomically.
        if (!copyToInternal) Log.d(TAG, "External model links are staged privately")
        val alreadyPrivate = ModelIntegrity.isContained(modelsDir, file)
        val staged = if (alreadyPrivate) {
            val inspection = ModelIntegrity.inspect(file, expectedSha256)
            StagedModel(file.canonicalFile, inspection)
        } else if (file.isDirectory) {
            ModelIntegrity.stageDirectory(file, modelsDir, file.name, expectedSha256)
        } else {
            ModelIntegrity.stageFile(file, modelsDir, file.name, expectedSha256)
        }
        return@withContext registerStagedModelInternal(staged, deleteIfRejected = !alreadyPrivate)
    }

    suspend fun importModel(
        uri: Uri,
        expectedSha256: String? = null
    ): RegisteredModel? = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, uri)
            ?: throw ModelIntegrityException("Invalid model URI")
        if (!document.isFile) throw ModelIntegrityException("Model URI is not a file")
        val name = document.name ?: "model_${System.currentTimeMillis()}"
        val staged = context.contentResolver.openInputStream(uri)?.use { input ->
            ModelIntegrity.stageFile(input, modelsDir, name, expectedSha256)
        } ?: throw ModelIntegrityException("Cannot open model URI")
        registerStagedModelInternal(staged, deleteIfRejected = true)
    }

    suspend fun importModelDirectory(
        uri: Uri,
        expectedSha256: String? = null
    ): RegisteredModel? = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromTreeUri(context, uri)
            ?: throw ModelIntegrityException("Invalid model directory URI")
        if (!document.isDirectory) throw ModelIntegrityException("Model URI is not a directory")
        val name = document.name ?: "model_${System.currentTimeMillis()}"
        val staged = ModelIntegrity.stageDirectory(modelsDir, name, expectedSha256) { sink ->
            copyDocumentTree(document, "", sink, 0)
        }
        // The translation-model bundle script writes a manifest.json with
        // per-file SHA-256s pinned from the publisher. If it is present, the
        // import is only trustworthy when every file matches — otherwise the
        // whole bundle is publisher-pinned evidence (not TOFU) and must be
        // rejected rather than silently downgraded.
        val stagedDir = staged.file
        val manifest = File(stagedDir, "manifest.json")
        if (manifest.exists()) {
            verifyBundleManifest(manifest, stagedDir)
        }
        registerStagedModelInternal(staged, deleteIfRejected = true)
    }

    /**
     * Verifies every per-file SHA-256 in a bundle manifest (format written by
     * scripts/make-translate-model.sh: `files` maps file name → hex digest,
     * plus a `__note__` entry to skip). Throws on any mismatch so the staged
     * directory is deleted by the caller's reject path.
     */
    private fun verifyBundleManifest(manifest: File, stagedDir: File) {
        val files = runCatching {
            org.json.JSONObject(manifest.readText()).optJSONObject("files")
        }.getOrElse {
            throw ModelIntegrityException("Bundle manifest.json is not valid JSON")
        } ?: throw ModelIntegrityException("Bundle manifest.json has no files map")

        val names = files.keys().asSequence().filter { it != "__note__" }.toList()
        if (names.isEmpty()) {
            throw ModelIntegrityException("Bundle manifest.json lists no files")
        }
        for (fileName in names) {
            val expected = files.optString(fileName)
            if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) {
                throw ModelIntegrityException("Manifest entry '$fileName' has no valid sha256")
            }
            val target = File(stagedDir, fileName)
            if (!ModelIntegrity.isContained(stagedDir, target) || !target.isFile) {
                throw ModelIntegrityException("Manifest lists missing file '$fileName'")
            }
            val actual = sha256OfFile(target)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw ModelIntegrityException(
                    "Integrity check failed for '$fileName': manifest digest does not match",
                )
            }
        }
    }

    private fun sha256OfFile(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun registerStagedModel(
        staged: StagedModel
    ): RegisteredModel? = withContext(Dispatchers.IO) {
        if (!ModelIntegrity.isContained(modelsDir, staged.file)) {
            throw ModelIntegrityException("Staged model is outside the private model root")
        }
        registerStagedModelInternal(staged, deleteIfRejected = false)
    }
    
    /**
     * Register a model from app assets.
     * This is for built-in models that ship with the APK.
     */
    fun registerAssetModel(
        id: String,
        name: String,
        engineType: ModelEngineType,
        assetPath: String,
        extractedPath: String? = null
    ): RegisteredModel {
        // Check if already registered
        val existing = _registeredModels.value.find { it.id == id }
        if (existing != null) return existing

        val extractedFile = extractedPath?.let(::File)
        val extractedInspection = extractedFile?.takeIf { file ->
            file.exists() && (
                ModelIntegrity.isContained(context.filesDir, file) ||
                    ModelIntegrity.isContained(context.cacheDir, file)
                )
        }?.let { file -> runCatching { ModelIntegrity.inspect(file) }.getOrNull() }
        
        val model = RegisteredModel(
            id = id,
            name = name,
            engineType = engineType,
            source = ModelSource.ASSET,
            path = extractedPath ?: assetPath,
            sizeBytes = extractedInspection?.sizeBytes ?: -1,
            isDirectory = engineType == ModelEngineType.VOSK,
            isValid = extractedInspection != null,
            addedTimestamp = System.currentTimeMillis(),
            digest = extractedInspection?.digest,
            metadata = mapOf(
                "assetPath" to assetPath,
                "integrityStatus" to if (extractedInspection == null) "not-extracted" else "verified"
            )
        )
        
        addModel(model)
        return model
    }
    
    /**
     * Unregister a model.
     * 
     * @param modelId The model ID to remove
     * @param deleteFiles If true, also deletes the model files from internal storage
     */
    suspend fun unregisterModel(modelId: String, deleteFiles: Boolean = false) = withContext(Dispatchers.IO) {
        val model = _registeredModels.value.find { it.id == modelId } ?: return@withContext
        
        if (deleteFiles && model.source == ModelSource.INTERNAL) {
            val file = File(model.path)
            if (file.exists() && ModelIntegrity.isContained(modelsDir, file) && file != modelsDir) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        }
        
        removeModel(modelId)
    }
    
    // =============================================================================
    // Queries
    // =============================================================================
    
    /**
     * Get all models for a specific engine type.
     */
    fun getModelsForEngine(engineType: ModelEngineType): List<RegisteredModel> {
        return _registeredModels.value.filter { it.engineType == engineType && it.isValid }
    }
    
    /**
     * Get a specific model by ID.
     */
    fun getModel(modelId: String): RegisteredModel? {
        return _registeredModels.value.find { it.id == modelId }
    }
    
    /**
     * Get a ModelProvider for a registered model.
     */
    fun getProvider(modelId: String): ModelProvider? {
        val model = getModel(modelId) ?: return null
        
        return object : ModelProvider {
            override fun getModelPath(): String {
                val file = File(model.path)
                if (!file.exists()) {
                    throw com.sal7one.common_jni.model.ModelNotFoundException(
                        "Model file not found: ${model.path}"
                    )
                }
                val expected = model.digest
                    ?: throw com.sal7one.common_jni.model.ModelNotFoundException(
                        "Model has not completed integrity registration: ${model.name}"
                    )
                try {
                    ModelIntegrity.verify(file, expected)
                } catch (e: Exception) {
                    throw com.sal7one.common_jni.model.ModelNotFoundException(
                        "Model integrity verification failed: ${model.name}",
                        e
                    )
                }
                return model.path
            }
            
            override fun exists(): Boolean = File(model.path).exists()
            
            override fun getSizeBytes(): Long = model.sizeBytes
            
            override fun getMetadata(): ModelMetadata = ModelMetadata(
                name = model.name,
                type = when (model.engineType) {
                    ModelEngineType.WHISPER -> ModelType.WHISPER
                    ModelEngineType.VOSK -> ModelType.VOSK
                    ModelEngineType.ONNX -> ModelType.ONNX
                    ModelEngineType.TRANSLATE -> ModelType.ONNX // OPUS-MT runs on ONNX Runtime
                },
                sizeBytes = model.sizeBytes,
                source = model.source.name.lowercase(),
                digest = model.digest
            )

            override fun getModelDigest(): ModelDigest? = model.digest
        }
    }
    
    /**
     * Get a FileModelProvider for a registered model path.
     */
    fun getFileProvider(modelId: String): FileModelProvider? {
        val model = getModel(modelId) ?: return null
        return FileModelProvider(model.path, model.digest)
    }
    
    /**
     * Check if a model exists and is valid.
     */
    fun isModelAvailable(modelId: String): Boolean {
        val model = getModel(modelId) ?: return false
        return model.isValid && File(model.path).exists()
    }
    
    // =============================================================================
    // Scan & Refresh
    // =============================================================================
    
    /**
     * Scan the models directory and update registrations.
     * Removes models that no longer exist, adds new ones found.
     */
    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        val refreshedModels = _registeredModels.value.map { model ->
            refreshRegisteredModel(model)
        }
        
        // Scan for new models in internal storage
        val existingPaths = refreshedModels.mapNotNull { model ->
            runCatching { File(model.path).canonicalPath }.getOrNull()
        }.toSet()
        val existingDigests = refreshedModels.mapNotNull { it.digest?.deduplicationKey() }.toMutableSet()
        val newModels = mutableListOf<RegisteredModel>()
        
        modelsDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(".model-") || file.canonicalPath in existingPaths) {
                return@forEach
            }
            runCatching {
                val inspection = ModelIntegrity.inspect(file)
                val analysis = analyzeModelFile(file, inspection.sizeBytes)
                if (analysis != null && existingDigests.add(inspection.digest.deduplicationKey())) {
                    newModels += RegisteredModel(
                        id = generateModelId(file.name, analysis.engineType, inspection.digest),
                        name = file.name,
                        engineType = analysis.engineType,
                        source = ModelSource.INTERNAL,
                        path = file.canonicalPath,
                        sizeBytes = inspection.sizeBytes,
                        isDirectory = file.isDirectory,
                        isValid = analysis.isValid,
                        addedTimestamp = System.currentTimeMillis(),
                        digest = inspection.digest,
                        metadata = analysis.metadata
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Skipping invalid private model ${file.name}: ${error.message}")
            }
        }
        
        _registeredModels.value = (refreshedModels + newModels).distinctBy { model ->
            model.digest?.deduplicationKey() ?: "id:${model.id}"
        }
        saveRegisteredModels()
    }
    
    // =============================================================================
    // Internal Helpers
    // =============================================================================
    
    private data class ModelAnalysis(
        val engineType: ModelEngineType,
        val isValid: Boolean,
        val sizeBytes: Long,
        val metadata: Map<String, String>
    )

    @Synchronized
    private fun registerStagedModelInternal(
        staged: StagedModel,
        deleteIfRejected: Boolean
    ): RegisteredModel? {
        val file = staged.file.canonicalFile
        if (file == modelsDir.canonicalFile || !ModelIntegrity.isContained(modelsDir, file)) {
            if (deleteIfRejected) deletePrivateModel(file)
            throw ModelIntegrityException("Staged model is outside the private model root")
        }

        return try {
            val verified = ModelIntegrity.verify(file, staged.digest)
            val duplicate = _registeredModels.value.find {
                it.digest?.deduplicationKey() == verified.digest.deduplicationKey()
            }
            if (duplicate != null) {
                if (deleteIfRejected && File(duplicate.path).canonicalFile != file) {
                    deletePrivateModel(file)
                }
                return duplicate
            }

            val analysis = analyzeModelFile(file, verified.sizeBytes)
            if (analysis == null) {
                if (deleteIfRejected) deletePrivateModel(file)
                return null
            }

            RegisteredModel(
                id = generateModelId(file.name, analysis.engineType, verified.digest),
                name = file.name,
                engineType = analysis.engineType,
                source = ModelSource.INTERNAL,
                path = file.canonicalPath,
                sizeBytes = verified.sizeBytes,
                isDirectory = verified.digest.kind == ModelDigestKind.TREE,
                isValid = analysis.isValid,
                addedTimestamp = System.currentTimeMillis(),
                digest = verified.digest,
                metadata = analysis.metadata + integrityMetadata(verified.digest)
            ).also(::addModel)
        } catch (e: Exception) {
            if (deleteIfRejected) deletePrivateModel(file)
            throw e
        }
    }

    private fun copyDocumentTree(
        directory: DocumentFile,
        relativeParent: String,
        sink: ModelIntegrity.DirectorySink,
        depth: Int
    ) {
        if (depth > ModelIntegrity.MAX_RELATIVE_DEPTH) {
            throw ModelIntegrityException(
                "Model tree exceeds depth ${ModelIntegrity.MAX_RELATIVE_DEPTH}"
            )
        }
        directory.listFiles().forEach { child ->
            val childName = child.name ?: throw ModelIntegrityException("Model entry has no name")
            ModelIntegrity.requireSafeName(childName)
            val relative = if (relativeParent.isEmpty()) childName else "$relativeParent/$childName"
            when {
                child.isDirectory -> {
                    sink.addDirectory(relative)
                    copyDocumentTree(child, relative, sink, depth + 1)
                }
                child.isFile -> context.contentResolver.openInputStream(child.uri)?.use { input ->
                    sink.addFile(relative, input)
                } ?: throw ModelIntegrityException("Cannot open model entry")
                else -> throw ModelIntegrityException("Model tree contains a non-regular entry")
            }
        }
    }

    private fun refreshRegisteredModel(model: RegisteredModel): RegisteredModel {
        if (model.source == ModelSource.ASSET) return model

        val original = File(model.path)
        if (!original.exists()) {
            return model.invalidated("missing")
        }

        return try {
            val alreadyPrivate = ModelIntegrity.isContained(modelsDir, original)
            val staged = when {
                alreadyPrivate -> {
                    val inspection = model.digest?.let { ModelIntegrity.verify(original, it) }
                        ?: ModelIntegrity.inspect(original)
                    StagedModel(original.canonicalFile, inspection)
                }
                original.isDirectory -> {
                    ModelIntegrity.stageDirectory(
                        original,
                        modelsDir,
                        original.name,
                        model.digest?.hex
                    ).preservingTrust(model.digest)
                }
                else -> {
                    ModelIntegrity.stageFile(
                        original,
                        modelsDir,
                        original.name,
                        model.digest?.hex
                    ).preservingTrust(model.digest)
                }
            }

            val analysis = analyzeModelFile(staged.file, staged.sizeBytes)
            if (analysis == null) {
                if (!alreadyPrivate) deletePrivateModel(staged.file)
                return model.invalidated("unsupported-structure")
            }

            model.copy(
                name = staged.file.name,
                engineType = analysis.engineType,
                source = ModelSource.INTERNAL,
                path = staged.file.canonicalPath,
                sizeBytes = staged.sizeBytes,
                isDirectory = staged.digest.kind == ModelDigestKind.TREE,
                isValid = analysis.isValid,
                digest = staged.digest,
                metadata = analysis.metadata + integrityMetadata(staged.digest)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Model refresh failed for ${model.name}: ${e.message}")
            model.invalidated("verification-failed")
        }
    }

    private fun StagedModel.preservingTrust(previous: ModelDigest?): StagedModel {
        if (previous == null || digest.trust == previous.trust) return this
        return copy(inspection = inspection.copy(digest = digest.copy(trust = previous.trust)))
    }

    private fun RegisteredModel.invalidated(status: String): RegisteredModel = copy(
        isValid = false,
        metadata = metadata + ("integrityStatus" to status)
    )

    private fun integrityMetadata(digest: ModelDigest): Map<String, String> = mapOf(
        "integrityAlgorithm" to digest.algorithm.name,
        "integrityTrust" to digest.trust.name,
        "integrityStatus" to "verified"
    )

    private fun ModelDigest.deduplicationKey(): String = "$algorithm:$kind:$hex"

    private fun deletePrivateModel(file: File) {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (canonical == modelsDir.canonicalFile || !ModelIntegrity.isContained(modelsDir, canonical)) return
        if (canonical.isDirectory) canonical.deleteRecursively() else canonical.delete()
    }
    
    private fun analyzeModelFile(file: File, verifiedSizeBytes: Long): ModelAnalysis? {
        val name = file.name.lowercase()
        val isDir = file.isDirectory
        val size = verifiedSizeBytes
        
        return when {
            // Whisper GGML: single .bin file
            name.endsWith(".bin") && !isDir -> {
                val valid = file.length() > 1024 * 1024 // At least 1MB
                ModelAnalysis(
                    engineType = ModelEngineType.WHISPER,
                    isValid = valid,
                    sizeBytes = size,
                    metadata = mapOf("format" to "GGML")
                )
            }
            
            // Vosk: directory with am/ and graph/
            isDir && (File(file, "am").exists() || File(file, "graph").exists()) -> {
                val hasAm = File(file, "am").exists()
                val hasGraph = File(file, "graph").exists()
                ModelAnalysis(
                    engineType = ModelEngineType.VOSK,
                    isValid = hasAm && hasGraph,
                    sizeBytes = size,
                    metadata = mapOf(
                        "hasAm" to hasAm.toString(),
                        "hasGraph" to hasGraph.toString(),
                        "hasConf" to File(file, "conf").exists().toString()
                    )
                )
            }
            
            // Marian (OPUS-MT) translation folder: source.spm + tokenizer.json
            // + encoder_model*.onnx + decoder_model_merged*.onnx. Checked
            // before the generic ONNX directory so translation folders are
            // routed to the translation runtime, not the STT ONNX engine.
            isDir && (File(file, "source.spm").exists() || File(file, "tokenizer.json").exists()) -> {
                val onnxFiles = file.listFiles()?.filter { it.name.endsWith(".onnx") } ?: emptyList()
                val hasEncoder = onnxFiles.any { it.name.contains("encoder_model", ignoreCase = true) }
                val hasDecoder = onnxFiles.any { it.name.contains("decoder_model_merged", ignoreCase = true) }
                val hasSpm = File(file, "source.spm").exists()
                val hasTokenizer = File(file, "tokenizer.json").exists()

                ModelAnalysis(
                    engineType = ModelEngineType.TRANSLATE,
                    isValid = hasEncoder && hasDecoder && hasSpm && hasTokenizer,
                    sizeBytes = size,
                    metadata = mapOf(
                        "hasEncoder" to hasEncoder.toString(),
                        "hasDecoder" to hasDecoder.toString(),
                        "hasSpm" to hasSpm.toString(),
                        "hasTokenizer" to hasTokenizer.toString(),
                        "onnxFiles" to onnxFiles.joinToString(",") { it.name }
                    )
                )
            }

            isDir && File(file, "model.onnx").isFile && File(file, "tokens.txt").isFile ->
                ModelAnalysis(ModelEngineType.ONNX, true, size, mapOf("format" to "VITS"))
            else -> null
        }
    }
    
    private fun generateModelId(
        fileName: String,
        engineType: ModelEngineType,
        digest: ModelDigest
    ): String {
        val baseName = fileName.substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9]"), "-")
            .lowercase()
        return "${engineType.name.lowercase()}-$baseName-${digest.hex.take(12)}"
    }
    
    private fun addModel(model: RegisteredModel) {
        val current = _registeredModels.value.toMutableList()
        // Remove any existing model with same ID
        current.removeAll { it.id == model.id }
        current.add(model)
        _registeredModels.value = current
        saveRegisteredModels()
    }
    
    private fun removeModel(modelId: String) {
        _registeredModels.value = _registeredModels.value.filter { it.id != modelId }
        saveRegisteredModels()
    }
    
    private fun loadRegisteredModels() {
        val json = prefs.getString(KEY_REGISTERED_MODELS, null) ?: return
        
        try {
            val array = JSONArray(json)
            val models = mutableListOf<RegisteredModel>()
            
            for (i in 0 until array.length()) {
                RegisteredModel.fromJson(array.getJSONObject(i))?.let { models.add(it) }
            }
            
            _registeredModels.value = models
            Log.d(TAG, "Loaded ${models.size} registered models")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load registered models: ${e.message}")
        }
    }
    
    private fun saveRegisteredModels() {
        val array = JSONArray()
        _registeredModels.value.forEach { model ->
            array.put(model.toJson())
        }
        
        prefs.edit()
            .putString(KEY_REGISTERED_MODELS, array.toString())
            .apply()
        
        Log.d(TAG, "Saved ${_registeredModels.value.size} registered models")
    }
}
