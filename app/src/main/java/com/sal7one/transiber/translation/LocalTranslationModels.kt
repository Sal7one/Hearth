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
    fun import(input: InputStream, spec: TranslationModelSpec, checkActive: () -> Unit = {}): File {
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val result = ModelIntegrity.stageFile(input, staging, "weights.gguf", spec.sha256, spec.bytes) { checkActive() }
            check(result.file.length() == spec.bytes) { "Translation GGUF has the wrong size" }
            checkActive()
            val destination = file(spec)
            if (destination.exists()) { ModelIntegrity.inspect(destination, spec.sha256); return destination }
            Files.move(result.file.toPath(), destination.toPath())
            return destination
        } finally { staging.deleteRecursively() }
    }
}
