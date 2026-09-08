package com.sal7one.transiber.model
import android.content.Context
import android.util.Log
import com.sal7one.common_jni.model.AssetModelProvider
import com.sal7one.common_jni.model.InternalStorageModelProvider
import com.sal7one.common_jni.model.ModelDigest
import com.sal7one.common_jni.model.ModelDigestAlgorithm
import com.sal7one.common_jni.model.ModelDigestKind
import com.sal7one.common_jni.model.ModelDigestTrust
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.model.ModelIntegrityException
import com.sal7one.common_jni.model.ModelMetadata
import com.sal7one.common_jni.model.ModelProvider
import com.sal7one.common_jni.model.ModelType as CommonModelType
import java.io.File

class VoskAssetModelProvider(
    private val context: Context,
    private val assetDirPath: String
) : ModelProvider {
    
    companion object {
        private const val TAG = "VoskAssetModelProvider"
    }
    
    private val modelName = assetDirPath.substringAfterLast("/")
    private val privateModelsDir = File(context.filesDir, "models")
    private val anchorPreferences = context.getSharedPreferences(
        "model_integrity_anchors",
        Context.MODE_PRIVATE
    )
    private val anchorKey = "vosk:$assetDirPath"
    @Volatile private var targetDir = readAnchor()?.file ?: File(privateModelsDir, modelName)
    @Volatile private var observedDigest: ModelDigest? = null
    @Volatile private var observedSizeBytes: Long = -1
    
    @Synchronized
    override fun getModelPath(): String {
        if (!exists()) {
            throw com.sal7one.common_jni.model.ModelNotFoundException(
                "Vosk model not found: $assetDirPath. Call prepareModel() first."
            )
        }
        try {
            val expected = observedDigest ?: readAnchor()?.digest
                ?: throw ModelIntegrityException(
                    "Vosk model has no persisted integrity anchor; call prepareModel()"
                )
            val inspection = ModelIntegrity.verify(targetDir, expected)
            observedDigest = inspection.digest
            observedSizeBytes = inspection.sizeBytes
        } catch (e: Exception) {
            throw com.sal7one.common_jni.model.ModelNotFoundException(
                "Vosk model integrity verification failed: $assetDirPath",
                e
            )
        }
        return targetDir.absolutePath
    }
    
    override fun exists(): Boolean {
        // Check if model directory exists and has required subdirs
        return targetDir.exists() && 
               File(targetDir, "am").exists() &&
               File(targetDir, "graph").exists()
    }
    
    override fun getSizeBytes(): Long {
        return observedSizeBytes
    }

    override fun getMetadata(): ModelMetadata = ModelMetadata(
        name = modelName,
        type = CommonModelType.VOSK,
        sizeBytes = observedSizeBytes,
        source = "asset:$assetDirPath",
        digest = observedDigest
    )

    override fun getModelDigest(): ModelDigest? = observedDigest
    
    /**
     * Copy the entire Vosk model directory from assets to internal storage.
     */
    @Synchronized
    fun prepareModel(expectedSha256: String? = null): Boolean {
        val persisted = readAnchor()
        if (persisted != null) targetDir = persisted.file

        val hasAnchor = expectedSha256 != null || observedDigest != null || persisted != null
        if (targetDir.exists() && exists() && hasAnchor) {
            return try {
                val inspection = when {
                    expectedSha256 != null -> ModelIntegrity.inspect(targetDir, expectedSha256)
                    observedDigest != null -> ModelIntegrity.verify(targetDir, observedDigest!!)
                    else -> ModelIntegrity.verify(targetDir, requireNotNull(persisted).digest)
                }
                observedDigest = inspection.digest
                observedSizeBytes = inspection.sizeBytes
                if (!writeAnchor(targetDir, inspection.digest)) {
                    throw ModelIntegrityException("Cannot persist Vosk model integrity anchor")
                }
                Log.d(TAG, "Verified prepared model: $modelName")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Prepared model failed integrity verification", e)
                false
            }
        }

        if (targetDir.exists()) {
            // A tree with an anchor is a detected mutation, not a new TOFU
            // candidate. Only unanchored legacy extracts are replaced.
            if (persisted != null || observedDigest != null || expectedSha256 != null) {
                Log.e(TAG, "Prepared Vosk model has invalid structure: $modelName")
                return false
            }
            if (!targetDir.deleteRecursively()) {
                Log.e(TAG, "Cannot replace unanchored legacy Vosk model: $modelName")
                return false
            }
        } else if (persisted != null) {
            clearAnchor()
        }
        
        Log.d(TAG, "Preparing Vosk model: $assetDirPath -> ${targetDir.absolutePath}")
        
        var published: File? = null
        return try {
            val staged = ModelIntegrity.stageDirectory(
                privateRoot = privateModelsDir,
                requestedName = modelName,
                expectedSha256 = expectedSha256
            ) { sink ->
                copyAssetTree(assetDirPath, "", sink, 0)
            }
            published = staged.file
            val verified = ModelIntegrity.verify(staged.file, staged.digest)
            targetDir = staged.file
            observedDigest = verified.digest
            observedSizeBytes = verified.sizeBytes
            if (!writeAnchor(staged.file, verified.digest)) {
                throw ModelIntegrityException("Cannot persist Vosk model integrity anchor")
            }
            Log.d(TAG, "Model prepared successfully: $modelName")
            true
        } catch (e: Exception) {
            published?.deleteRecursively()
            clearAnchor()
            Log.e(TAG, "Failed to prepare model: ${e.message}", e)
            false
        }
    }
    
    private fun copyAssetTree(
        assetPath: String,
        relativePath: String,
        sink: ModelIntegrity.DirectorySink,
        depth: Int
    ) {
        if (depth > ModelIntegrity.MAX_RELATIVE_DEPTH) {
            throw ModelIntegrityException(
                "Model tree exceeds depth ${ModelIntegrity.MAX_RELATIVE_DEPTH}"
            )
        }
        val children = context.assets.list(assetPath)
            ?: throw ModelIntegrityException("Cannot list model asset directory")
        if (children.isEmpty()) {
            if (relativePath.isEmpty()) throw ModelIntegrityException("Model asset directory is empty")
            try {
                context.assets.open(assetPath).use { sink.addFile(relativePath, it) }
            } catch (e: java.io.FileNotFoundException) {
                sink.addDirectory(relativePath)
            }
            return
        }

        if (relativePath.isNotEmpty()) sink.addDirectory(relativePath)
        children.forEach { childName ->
            ModelIntegrity.requireSafeName(childName)
            val childAssetPath = "$assetPath/$childName"
            val childRelativePath = if (relativePath.isEmpty()) {
                childName
            } else {
                "$relativePath/$childName"
            }
            copyAssetTree(childAssetPath, childRelativePath, sink, depth + 1)
        }
    }

    private data class PersistedAnchor(val file: File, val digest: ModelDigest)

    private fun readAnchor(): PersistedAnchor? {
        val path = anchorPreferences.getString("$anchorKey:path", null) ?: return null
        val hex = anchorPreferences.getString("$anchorKey:hex", null) ?: return null
        val trust = anchorPreferences.getString("$anchorKey:trust", null) ?: return null
        return runCatching {
            val root = privateModelsDir.canonicalFile
            val file = File(path).canonicalFile
            require(file.parentFile == root && ModelIntegrity.isContained(root, file))
            PersistedAnchor(
                file = file,
                digest = ModelDigest(
                    algorithm = ModelDigestAlgorithm.SHA256,
                    kind = ModelDigestKind.TREE,
                    hex = hex,
                    trust = ModelDigestTrust.valueOf(trust)
                )
            )
        }.getOrElse {
            clearAnchor()
            null
        }
    }

    private fun writeAnchor(file: File, digest: ModelDigest): Boolean {
        if (digest.kind != ModelDigestKind.TREE) return false
        return anchorPreferences.edit()
            .putString("$anchorKey:path", file.canonicalPath)
            .putString("$anchorKey:hex", digest.hex)
            .putString("$anchorKey:trust", digest.trust.name)
            .commit()
    }

    private fun clearAnchor() {
        anchorPreferences.edit()
            .remove("$anchorKey:path")
            .remove("$anchorKey:hex")
            .remove("$anchorKey:trust")
            .commit()
    }
}

