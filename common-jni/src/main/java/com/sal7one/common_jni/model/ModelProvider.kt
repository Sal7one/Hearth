package com.sal7one.common_jni.model

import android.content.Context
import java.io.File

/**
 * Interface for providing model files to common-jni engines.
 * 
 * Developers implement this to load models from:
 * - App assets
 * - App internal/external storage
 * - Downloaded files
 * - Content URIs
 * - Network (cached locally)
 * 
 * Usage:
 * ```kotlin
 * // From assets
 * val provider = AssetModelProvider(context, "models/whisper-tiny.bin")
 * 
 * // From file path
 * val provider = FileModelProvider("/sdcard/Download/model.bin")
 * 
 * // Custom provider
 * val provider = object : ModelProvider {
 *     override fun getModelPath(): String = downloadAndCache()
 *     override fun exists(): Boolean = checkCache()
 * }
 * 
 * // Use with engine
 * val engine = WhisperEngine().apply { initialize(provider.getModelPath(), SttConfig()) }
 * ```
 */
interface ModelProvider {
    /**
     * Get the absolute path to the model file.
     * 
     * For assets, this should extract to cache first and return cache path.
     * For files, return the absolute path directly.
     * 
     * @return Absolute path to model file
     * @throws ModelNotFoundException if model doesn't exist
     */
    @Throws(ModelNotFoundException::class)
    fun getModelPath(): String
    
    /**
     * Check if the model exists and is accessible.
     */
    fun exists(): Boolean
    
    /**
     * Get the model size in bytes, or -1 if unknown.
     */
    fun getSizeBytes(): Long = -1
    
    /**
     * Get model metadata (optional).
     */
    fun getMetadata(): ModelMetadata? = null

    /**
     * Integrity metadata for the exact bytes returned by [getModelPath].
     * A TOFU digest detects later changes but does not authenticate first use.
     */
    fun getModelDigest(): ModelDigest? = getMetadata()?.digest
    
    /**
     * Release any resources (e.g., extracted temp files).
     */
    fun release() {}
}

/**
 * Model metadata for display/logging purposes.
 */
data class ModelMetadata(
    val name: String,
    val version: String? = null,
    val type: ModelType = ModelType.UNKNOWN,
    val language: String? = null,
    val sizeBytes: Long = -1,
    val quantization: String? = null,  // e.g., "q4_0", "q8_0", "f16"
    val source: String? = null,        // e.g., "huggingface", "local"
    val digest: ModelDigest? = null
)

enum class ModelType {
    WHISPER,
    VOSK,
    ONNX,
    YOLO,
    OPENCV_DNN,
    UNKNOWN
}

class ModelNotFoundException(message: String, cause: Throwable? = null) : Exception(message, cause)

// =============================================================================
// Built-in Providers
// =============================================================================

/**
 * Load model from app assets.
 * Extracts to cache directory on first access.
 * 
 * @param context Application context
 * @param assetPath Path within assets folder (e.g., "models/whisper-tiny.bin")
 * @param cacheSubdir Subdirectory in cache (default: "models")
 */
class AssetModelProvider(
    private val context: Context,
    private val assetPath: String,
    private val cacheSubdir: String = "models",
    private val expectedSha256: String? = null
) : ModelProvider {
    
    private var cachedPath: String? = null
    @Volatile private var observedDigest: ModelDigest? = null
    
    @Synchronized
    override fun getModelPath(): String {
        val cacheDir = File(context.cacheDir, cacheSubdir)
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = File(cacheDir, fileName)

        cachedPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                observedDigest = observedDigest?.let { ModelIntegrity.verify(file, it).digest }
                    ?: ModelIntegrity.inspect(file, expectedSha256).digest
                return file.absolutePath
            }
        }

        if (cacheFile.exists()) {
            val assetInspection = try {
                context.assets.open(assetPath).use { input ->
                    ModelIntegrity.inspect(input, expectedSha256)
                }
            } catch (e: Exception) {
                throw ModelNotFoundException("Failed to verify bundled asset: $assetPath", e)
            }
            try {
                val cachedInspection = ModelIntegrity.verify(cacheFile, assetInspection.digest)
                observedDigest = cachedInspection.digest
                cachedPath = cacheFile.absolutePath
                return cacheFile.absolutePath
            } catch (e: Exception) {
                if (!cacheFile.delete()) {
                    throw ModelNotFoundException("Cannot replace invalid cached asset: $assetPath", e)
                }
            }
        }

        return try {
            val staged = context.assets.open(assetPath).use { input ->
                ModelIntegrity.stageFile(input, cacheDir, fileName, expectedSha256)
            }
            observedDigest = staged.digest
            cachedPath = staged.file.absolutePath
            staged.file.absolutePath
        } catch (e: Exception) {
            throw ModelNotFoundException("Failed to extract verified asset: $assetPath", e)
        }
    }
    
    override fun exists(): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (e: Exception) {
            cachedPath?.let { File(it).exists() } ?: false
        }
    }
    
    override fun getSizeBytes(): Long {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            cachedPath?.let { File(it).length() } ?: -1
        }
    }

    override fun getMetadata(): ModelMetadata = ModelMetadata(
        name = assetPath.substringAfterLast('/'),
        sizeBytes = getSizeBytes(),
        source = "asset:$assetPath",
        digest = observedDigest
    )

    override fun getModelDigest(): ModelDigest? = observedDigest
    
    override fun release() {
        // Optionally delete cached file
        // cachedPath?.let { File(it).delete() }
    }
}

/**
 * Load model from file system path.
 * 
 * @param path Absolute path to model file
 */
class FileModelProvider(
    private val path: String,
    expectedDigest: ModelDigest? = null
) : ModelProvider {
    
    private val file = File(path)
    @Volatile private var observedDigest: ModelDigest? = expectedDigest
    
    @Synchronized
    override fun getModelPath(): String {
        if (!file.exists()) {
            throw ModelNotFoundException("Model file not found: $path")
        }
        observedDigest = observedDigest?.let { ModelIntegrity.verify(file, it).digest }
            ?: ModelIntegrity.inspect(file).digest
        return file.absolutePath
    }
    
    override fun exists(): Boolean = file.exists() && file.canRead()
    
    override fun getSizeBytes(): Long = if (file.exists()) file.length() else -1

    override fun getMetadata(): ModelMetadata = ModelMetadata(
        name = file.name,
        sizeBytes = getSizeBytes(),
        source = "file",
        digest = observedDigest
    )

    override fun getModelDigest(): ModelDigest? = observedDigest
}

/**
 * Load model from app's internal files directory.
 * 
 * @param context Application context
 * @param relativePath Path relative to filesDir (e.g., "models/whisper.bin")
 */
class InternalStorageModelProvider(
    private val context: Context,
    private val relativePath: String,
    expectedDigest: ModelDigest? = null
) : ModelProvider {

    @Volatile private var observedDigest: ModelDigest? = expectedDigest
    
    private val modelFile: File
        get() = File(context.filesDir, relativePath)
    
    @Synchronized
    override fun getModelPath(): String {
        if (!modelFile.exists()) {
            throw ModelNotFoundException("Model not found in internal storage: $relativePath")
        }
        observedDigest = observedDigest?.let { ModelIntegrity.verify(modelFile, it).digest }
            ?: ModelIntegrity.inspect(modelFile).digest
        return modelFile.absolutePath
    }
    
    override fun exists(): Boolean = modelFile.exists() && modelFile.canRead()
    
    override fun getSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else -1

    override fun getMetadata(): ModelMetadata = ModelMetadata(
        name = modelFile.name,
        sizeBytes = getSizeBytes(),
        source = "internal",
        digest = observedDigest
    )

    override fun getModelDigest(): ModelDigest? = observedDigest
    
    /**
     * Get the File object for downloading/writing.
     * Creates parent directories if needed.
     */
    fun getDestinationFile(): File {
        modelFile.parentFile?.mkdirs()
        return modelFile
    }
}

/** Copies an external model into app-private storage before publishing its path. */
class ExternalStorageModelProvider private constructor(
    private val path: String,
    private val expectedDigest: ModelDigest?,
    private val privateRoot: File?
) : ModelProvider {

    /**
     * Retained for source compatibility, but intentionally fails closed because
     * no app-private destination can be derived without a [Context].
     */
    @Deprecated(
        message = "Pass a Context so external models can be staged into app-private storage",
        replaceWith = ReplaceWith("ExternalStorageModelProvider(context, path, expectedDigest)")
    )
    constructor(path: String, expectedDigest: ModelDigest? = null) :
        this(path, expectedDigest, null)

    constructor(context: Context, path: String, expectedDigest: ModelDigest? = null) :
        this(path, expectedDigest, File(context.applicationContext.filesDir, "models"))

    private val source = File(path)
    @Volatile private var stagedModel: StagedModel? = null
    @Volatile private var observedDigest: ModelDigest? = expectedDigest

    @Synchronized
    override fun getModelPath(): String {
        stagedModel?.let { staged ->
            val verified = ModelIntegrity.verify(staged.file, staged.digest)
            observedDigest = verified.digest
            return staged.file.absolutePath
        }

        val destination = privateRoot ?: throw ModelNotFoundException(
            "External model providers require Context-backed app-private staging"
        )
        if (!source.exists()) {
            throw ModelNotFoundException("Model not found in external storage: $path")
        }
        if (!source.canRead()) {
            throw ModelNotFoundException("Cannot read model file (permission denied?): $path")
        }

        val copied = try {
            if (source.isDirectory) {
                ModelIntegrity.stageDirectory(
                    sourceRoot = source,
                    privateRoot = destination,
                    requestedName = source.name,
                    expectedSha256 = expectedDigest?.hex
                )
            } else {
                ModelIntegrity.stageFile(
                    source = source,
                    privateRoot = destination,
                    requestedName = source.name,
                    expectedSha256 = expectedDigest?.hex
                )
            }
        } catch (e: Exception) {
            throw ModelNotFoundException("Failed to stage external model privately: $path", e)
        }
        val staged = expectedDigest?.let { expected ->
            copied.copy(
                inspection = copied.inspection.copy(
                    digest = copied.digest.copy(trust = expected.trust)
                )
            )
        } ?: copied
        stagedModel = staged
        observedDigest = staged.digest
        return staged.file.absolutePath
    }

    override fun exists(): Boolean = privateRoot != null && source.exists() && source.canRead()

    override fun getSizeBytes(): Long = stagedModel?.sizeBytes
        ?: if (source.exists() && source.isFile) source.length() else -1

    override fun getMetadata(): ModelMetadata = ModelMetadata(
        name = source.name,
        sizeBytes = getSizeBytes(),
        source = "external",
        digest = observedDigest
    )

    override fun getModelDigest(): ModelDigest? = observedDigest

    @Synchronized
    override fun release() {
        stagedModel?.file?.deleteRecursively()
        stagedModel = null
        observedDigest = expectedDigest
    }
}

/**
 * Lazy model provider that downloads on first access.
 * 
 * @param context Application context
 * @param downloadUrl URL to download from
 * @param cacheFileName Local file name in cache
 * @param onProgress Progress callback (0.0 to 1.0)
 */
class DownloadableModelProvider(
    private val context: Context,
    private val downloadUrl: String,
    private val cacheFileName: String,
    private val onProgress: ((Float) -> Unit)? = null
) : ModelProvider {

    @Volatile private var observedDigest: ModelDigest? = null
    
    private val cacheFile: File
        get() = File(context.cacheDir, "models/$cacheFileName")
    
    @Synchronized
    override fun getModelPath(): String {
        if (cacheFile.exists()) {
            observedDigest = observedDigest?.let { ModelIntegrity.verify(cacheFile, it).digest }
                ?: ModelIntegrity.inspect(cacheFile).digest
            return cacheFile.absolutePath
        }
        
        throw ModelNotFoundException(
            "Model not downloaded yet. Call downloadIfNeeded() first. URL: $downloadUrl"
        )
    }
    
    override fun exists(): Boolean = cacheFile.exists()
    
    override fun getSizeBytes(): Long = if (cacheFile.exists()) cacheFile.length() else -1

    override fun getMetadata(): ModelMetadata = ModelMetadata(
        name = cacheFileName,
        sizeBytes = getSizeBytes(),
        source = downloadUrl,
        digest = observedDigest
    )

    override fun getModelDigest(): ModelDigest? = observedDigest
    
    /**
     * Download the model if not already cached.
     * 
     * @return true if download succeeded or already cached
     */
    suspend fun downloadIfNeeded(): Boolean {
        if (cacheFile.exists()) return true
        
        // Actual download implementation would go here
        // For now, just indicate that download is needed
        return false
    }
    
    /**
     * Get the expected download URL.
     */
    fun getDownloadUrl(): String = downloadUrl
    
    /**
     * Delete cached model.
     */
    override fun release() {
        cacheFile.delete()
    }
}
