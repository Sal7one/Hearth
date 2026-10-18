package com.sal7one.transiber.setup

import com.sal7one.common_jni.model.ModelInspection
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Screen-lifetime cache only. Preview checks metadata; Apply always rehashes the actual model bytes. */
internal class SetupModelVerifier {
    private data class Receipt<T>(val metadata: List<String>, val value: T)
    private val files = mutableMapOf<String, Receipt<ModelInspection>>()
    private val packages = mutableMapOf<String, Receipt<SpeechModelPackage>>()
    @Synchronized fun inspect(file: File, expected: String? = null, force: Boolean = false): ModelInspection {
        val key = file.absolutePath + ":" + expected.orEmpty()
        val before = metadata(file)
        files[key]?.takeIf { !force && it.metadata == before }?.let { return it.value }
        val result = ModelIntegrity.inspect(file, expected)
        check(metadata(file) == before) { "Model files changed during verification. Check again." }
        if (files.size >= 64) files.clear()
        files[key] = Receipt(before, result)
        return result
    }
    @Synchronized fun speech(root: File, force: Boolean = false): SpeechModelPackage {
        val before = metadata(root)
        packages[root.absolutePath]?.takeIf { !force && it.metadata == before }?.let { return it.value }
        val result = SpeechModelPackage.verify(root)
        check(metadata(root) == before) { "Speech package changed during verification. Check again." }
        if (packages.size >= 32) packages.clear()
        packages[root.absolutePath] = Receipt(before, result)
        return result
    }
    private fun metadata(file: File): List<String> {
        val root = file.toPath()
        require(Files.exists(root)) { "Model file is missing: ${file.name}" }
        val result = mutableListOf<String>()
        Files.walk(root).use { paths -> paths.forEach { path ->
            require(result.size < ModelIntegrity.MAX_TREE_FILES * 2) { "Model tree exceeds verification limits" }
            require(!Files.isSymbolicLink(path)) { "Symbolic link in model tree: ${path.fileName}" }
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            result += root.relativize(path).toString() + ":" + attributes.size() + ":" + attributes.lastModifiedTime() + ":" + attributes.fileKey()
        } }
        return result.sorted()
    }
}
