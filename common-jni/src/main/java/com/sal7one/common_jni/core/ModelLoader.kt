package com.sal7one.common_jni.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.sal7one.common_jni.model.ModelDigest
import com.sal7one.common_jni.model.ModelIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

enum class ModelSource {
    ASSETS,
    INTERNAL,
    EXTERNAL,
    CACHE
}

data class ModelInfo(
    val name: String,
    val path: String,
    val source: ModelSource,
    val sizeBytes: Long,
    val checksum: String? = null,
    val digest: ModelDigest? = null
)

typealias ModelLoadProgress = (bytesLoaded: Long, totalBytes: Long) -> Unit

/**
 * Unified model loader supporting:
 * - Assets (bundled models)
 * - Internal storage
 * - SAF (Storage Access Framework) for user-selected files
 * - Cache directory
 */
class ModelLoader(private val context: Context) {

    private val knownDigests = ConcurrentHashMap<String, ModelDigest>()
    
    private val cacheDir: File
        get() = File(context.cacheDir, "models").also { it.mkdirs() }
    
    /**
     * Load model from assets.
     * Copies to cache if needed for native access.
     */
    suspend fun loadFromAssets(
        assetPath: String,
        progress: ModelLoadProgress? = null,
        expectedSha256: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val filename = assetPath.substringAfterLast('/')
            val cacheFile = File(cacheDir, filename)
            val assetSize = context.assets.open(assetPath).use { it.available().toLong() }

            if (cacheFile.exists()) {
                val assetInspection = context.assets.open(assetPath).use { input ->
                    ModelIntegrity.inspect(input, expectedSha256) { loaded ->
                        progress?.invoke(loaded, assetSize)
                    }
                }
                val cachedInspection = try {
                    ModelIntegrity.verify(cacheFile, assetInspection.digest)
                } catch (error: Exception) {
                    if (!deleteCacheEntry(cacheFile)) throw error
                    null
                }
                if (cachedInspection != null) {
                    knownDigests[cacheFile.canonicalPath] = cachedInspection.digest
                    progress?.invoke(cachedInspection.sizeBytes, assetSize)
                    return@withContext Result.success(cacheFile.canonicalPath)
                }
            }

            val staged = context.assets.open(assetPath).use { input ->
                ModelIntegrity.stageFile(input, cacheDir, filename, expectedSha256) { loaded ->
                    progress?.invoke(loaded, assetSize)
                }
            }
            knownDigests[staged.file.canonicalPath] = staged.digest
            Result.success(staged.file.canonicalPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Load model from SAF Uri.
     * Copies to cache for native access.
     */
    suspend fun loadFromSAF(
        uri: Uri,
        progress: ModelLoadProgress? = null,
        expectedSha256: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
                ?: return@withContext Result.failure(Exception("Invalid URI"))

            val filename = docFile.name ?: "model_${System.currentTimeMillis()}"
            val totalSize = docFile.length()

            val staged = context.contentResolver.openInputStream(uri)?.use { input ->
                ModelIntegrity.stageFile(input, cacheDir, filename, expectedSha256) { loaded ->
                    progress?.invoke(loaded, totalSize)
                }
            } ?: return@withContext Result.failure(Exception("Cannot open URI"))
            knownDigests[staged.file.canonicalPath] = staged.digest
            Result.success(staged.file.canonicalPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Load model from direct file path.
     */
    fun loadFromPath(path: String, expectedSha256: String? = null): Result<String> {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            return Result.failure(Exception("File not found or not readable: $path"))
        }
        return try {
            val isPrivate = ModelIntegrity.isContained(context.filesDir, file) ||
                ModelIntegrity.isContained(context.cacheDir, file)
            if (isPrivate) {
                inspectKnown(file, expectedSha256)
                Result.success(file.canonicalPath)
            } else {
                val staged = if (file.isDirectory) {
                    ModelIntegrity.stageDirectory(file, cacheDir, file.name, expectedSha256)
                } else {
                    ModelIntegrity.stageFile(file, cacheDir, file.name, expectedSha256)
                }
                knownDigests[staged.file.canonicalPath] = staged.digest
                Result.success(staged.file.canonicalPath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * List models in assets directory.
     */
    fun listAssetModels(directory: String = "models"): List<ModelInfo> {
        return try {
            context.assets.list(directory)?.mapNotNull { name ->
                try {
                    val path = "$directory/$name"
                    val size = context.assets.open(path).use { it.available().toLong() }
                    ModelInfo(
                        name = name,
                        path = path,
                        source = ModelSource.ASSETS,
                        sizeBytes = size,
                        checksum = null,
                        digest = null
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * List cached models.
     */
    fun listCachedModels(): List<ModelInfo> {
        return cacheDir.listFiles()?.filterNot { it.name.startsWith(".model-") }?.mapNotNull { file ->
            runCatching {
                val inspection = inspectKnown(file, expectedSha256 = null)
                ModelInfo(
                    name = file.name,
                    path = file.absolutePath,
                    source = ModelSource.CACHE,
                    sizeBytes = inspection.sizeBytes,
                    checksum = inspection.digest.encoded,
                    digest = inspection.digest
                )
            }.getOrNull()
        } ?: emptyList()
    }
    
    /**
     * Get total cache size.
     */
    fun getCacheSize(): Long {
        return listCachedModels().sumOf { it.sizeBytes }
    }
    
    /**
     * Clear model cache.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { deleteCacheEntry(it) }
        knownDigests.clear()
    }
    
    /**
     * Delete specific cached model.
     */
    fun deleteCachedModel(name: String): Boolean {
        val file = safeCacheEntry(name) ?: return false
        val key = runCatching { file.canonicalPath }.getOrNull()
        val deleted = deleteCacheEntry(file)
        if (deleted && key != null) knownDigests.remove(key)
        return deleted
    }
    
    /**
     * Check if model is cached.
     */
    fun isCached(name: String): Boolean {
        return safeCacheEntry(name)?.exists() == true
    }
    
    /**
     * Get cached model path if exists.
     */
    fun getCachedPath(name: String): String? {
        val file = safeCacheEntry(name)?.takeIf { it.exists() } ?: return null
        return runCatching {
            inspectKnown(file, expectedSha256 = null)
            file.canonicalPath
        }.getOrNull()
    }

    private fun inspectKnown(file: File, expectedSha256: String?): com.sal7one.common_jni.model.ModelInspection {
        val key = file.canonicalPath
        val known = knownDigests[key]
        val inspection = when {
            expectedSha256 != null -> ModelIntegrity.inspect(file, expectedSha256)
            known != null -> ModelIntegrity.verify(file, known)
            else -> ModelIntegrity.inspect(file)
        }
        knownDigests[key] = inspection.digest
        return inspection
    }

    private fun safeCacheEntry(name: String): File? = runCatching {
        val safeName = ModelIntegrity.requireSafeName(name)
        val root = cacheDir.canonicalFile
        File(root, safeName).also { candidate ->
            require(candidate.absoluteFile.parentFile == root)
            require(ModelIntegrity.isContained(root, candidate))
        }
    }.getOrNull()

    private fun deleteCacheEntry(file: File): Boolean {
        val safe = safeCacheEntry(file.name) ?: return false
        if (!Files.exists(safe.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(safe.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        }.getOrDefault(false)
    }
}
