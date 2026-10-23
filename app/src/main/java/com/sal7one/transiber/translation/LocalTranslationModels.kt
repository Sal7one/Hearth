package com.sal7one.transiber.translation

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationModelSpec
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

/** Pinned raw-GGUF imports reuse the existing streaming integrity utility; no executable package metadata. */
class LocalTranslationModels(private val root: File) {
    fun file(spec: TranslationModelSpec) = File(root, "${spec.id}.gguf")
    fun installed() = TranslationCatalog.models.filter { file(it).isFile && file(it).length() == it.bytes }
    /** Batch imports identify a model by pinned size and SHA-256, even after a file was renamed. */
    fun importCatalog(input: InputStream, candidates: List<TranslationModelSpec> = TranslationCatalog.models,
        checkActive: () -> Unit = {}): Pair<TranslationModelSpec, File> {
        require(candidates.isNotEmpty()) { "The translation catalogue is empty" }
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val result = ModelIntegrity.stageFile(input, staging, "weights.gguf", maxBytes = candidates.maxOf { it.bytes }) { checkActive() }
            val spec = candidates.singleOrNull { it.bytes == result.inspection.sizeBytes && it.sha256.equals(result.inspection.digest.hex, ignoreCase = true) }
                ?: error("Translation file does not match a pinned Hearth GGUF model. Re-download the original model file.")
            checkActive()
            return spec to publish(result.file, spec, checkActive)
        } finally { staging.deleteRecursively() }
    }
    fun import(input: InputStream, spec: TranslationModelSpec, checkActive: () -> Unit = {}): File {
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val result = ModelIntegrity.stageFile(input, staging, "weights.gguf", spec.sha256, spec.bytes) { checkActive() }
            check(result.file.length() == spec.bytes) { "Translation GGUF has the wrong size" }
            checkActive()
            return publish(result.file, spec, checkActive)
        } finally { staging.deleteRecursively() }
    }
    private fun publish(staged: File, spec: TranslationModelSpec, checkActive: () -> Unit): File {
        checkActive()
        val destination = file(spec)
        if (destination.exists()) {
            try { ModelIntegrity.inspect(destination, spec.sha256); return destination }
            catch (_: com.sal7one.common_jni.model.ModelIntegrityException) {
                // The freshly staged publisher-pinned file repairs a damaged private copy.
            }
        }
        val damaged = File(root, ".damaged-${UUID.randomUUID()}")
        if (destination.exists()) Files.move(destination.toPath(), damaged.toPath())
        try { Files.move(staged.toPath(), destination.toPath()) }
        catch (e: Exception) {
            if (damaged.exists()) Files.move(damaged.toPath(), destination.toPath())
            throw e
        }
        damaged.deleteRecursively()
        return destination
    }
}
