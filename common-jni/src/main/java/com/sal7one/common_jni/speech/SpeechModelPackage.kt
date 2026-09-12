package com.sal7one.common_jni.speech

import com.sal7one.common_jni.model.ModelIntegrity
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Verified, immutable-by-contract app-private snapshot. Keep its files unchanged while a session is open. */
class SpeechModelPackage private constructor(
    val root: File,
    val profile: SpeechProfile,
    val sizeBytes: Long,
    val manifestSha256: String,
    internal val roles: Map<String, String>,
) {
    internal fun path(role: String): String = roles[role]?.let { File(root, it).absolutePath }.orEmpty()
    companion object {
        const val MANIFEST = "hearth-speech.json"
        /** IO operation. Does not download, unzip, or mutate the model directory. */
        fun verify(root: File): SpeechModelPackage {
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Speech package must be a real directory: $root" }
            val manifest = File(root, MANIFEST)
            require(manifest.isFile && manifest.length() in 1..65536) { "Missing or oversized $MANIFEST" }
            val manifestInspection = ModelIntegrity.inspect(manifest)
            val json = JSONObject(manifest.readText())
            require(json.get("schemaVersion") is Number && json.get("schemaVersion").toString() == "1") { "Unsupported speech package schema" }
            val profile = SpeechProfile.fromId(json.getString("profile"))
            val files = json.getJSONArray("files")
            require(files.length() in 1..1000) { "Speech package requires 1..1000 assets" }
            val declared = linkedSetOf<String>()
            var total = 0L
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val path = safeRelative(entry.getString("path"))
                require(path != MANIFEST && declared.add(path)) { "Duplicate/reserved speech asset: $path" }
                val sizeToken = entry.get("bytes")
                require(sizeToken is Number && sizeToken.toString().matches(Regex("[1-9][0-9]*"))) { "Asset bytes must be an integer: $path" }
                val bytes = entry.getLong("bytes")
                require(bytes in 1..ModelIntegrity.MAX_SINGLE_FILE_BYTES) { "Invalid size for speech asset: $path" }
                total = Math.addExact(total, bytes)
                require(total <= ModelIntegrity.MAX_TREE_BYTES) { "Speech package exceeds size limit" }
                val sha = entry.getString("sha256")
                require(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for speech asset: $path" }
                val file = File(root, path)
                require(ModelIntegrity.isContained(root, file)) { "Speech asset escapes package: $path" }
                // Every path component must be real; no hidden links into another model.
                var component = root.toPath()
                path.split('/').forEach { part ->
                    component = component.resolve(part)
                    require(!Files.isSymbolicLink(component)) { "Symbolic link in speech package: $path" }
                }
                require(file.isFile && file.length() == bytes) { "Speech asset missing or wrong size: $path" }
                ModelIntegrity.inspect(file, sha)
            }
            val actual = linkedSetOf<String>()
            var visited = 0
            Files.walk(root.toPath()).use { walk -> walk.forEach { path ->
                require(++visited <= 2000) { "Speech package exceeds entry count limit" }
                require(!Files.isSymbolicLink(path)) { "Symbolic link in speech package: $path" }
                if (Files.isRegularFile(path)) {
                    actual.add(root.toPath().relativize(path).toString().replace(File.separatorChar, '/'))
                    require(actual.size <= 1001) { "Speech package exceeds file count limit" }
                }
            } }
            require(actual == declared + MANIFEST) { "Speech package contains undeclared files: ${actual - declared - MANIFEST}" }
            val roleJson = json.getJSONObject("roles")
            val roles = roleJson.keys().asSequence().associateWith { safeRelative(roleJson.getString(it)) }
            val expected = if (profile.backend == SpeechBackend.MOONSHINE) setOf("model", "encoder", "decoder") else if (profile.backend == SpeechBackend.QWEN3_ASR) setOf("frontend", "encoder", "decoder", "tokenizer") else setOf("model")
            require(roles.keys == expected) { "${profile.id} requires roles: $expected" }
            roles.forEach { (role, path) ->
                if (role == "tokenizer") {
                    listOf("vocab.json", "merges.txt", "tokenizer_config.json").forEach { required ->
                        require("$path/$required" in declared) { "Missing Qwen tokenizer asset: $path/$required" }
                    }
                } else require(path in declared) { "Role $role references undeclared asset: $path" }
            }
            if (profile.backend == SpeechBackend.NEMOTRON_3_5) {
                val magic = ByteArray(4)
                File(root, roles.getValue("model")).inputStream().use { require(it.read(magic) == 4 && magic.contentEquals("GGUF".toByteArray())) { "Nemotron requires a GGUF model" } }
            }
            return SpeechModelPackage(root.absoluteFile, profile, total, manifestInspection.digest.hex, roles)
        }
        private fun safeRelative(path: String): String {
            require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path && ':' !in path) { "Invalid relative speech asset path: $path" }
            require(path.split('/').size <= ModelIntegrity.MAX_RELATIVE_DEPTH && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid relative speech asset path: $path" }
            return path
        }
    }
}
