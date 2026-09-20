package com.sal7one.transiber.shortcuts

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Fast existence/size check only; never replaces SpeechModelPackage's runtime hash verification. */
internal fun checkSpeechAssetPresence(root: File) {
    require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Speech package must be a real directory: $root" }
    val manifest = File(root, SpeechModelPackage.MANIFEST)
    require(manifest.isFile && manifest.length() in 1..65536) { "Missing or oversized ${SpeechModelPackage.MANIFEST}" }
    val files = JSONObject(manifest.readText()).getJSONArray("files")
    require(files.length() in 1..1000) { "Speech package requires 1..1000 assets" }
    for (i in 0 until files.length()) {
        val entry = files.getJSONObject(i)
        val path = entry.getString("path")
        val parts = path.split('/')
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && parts.all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid relative speech asset path: $path" }
        val asset = File(root, path)
        require(ModelIntegrity.isContained(root, asset)) { "Speech asset escapes package: $path" }
        var component = root.toPath()
        parts.forEach { component = component.resolve(it); require(!Files.isSymbolicLink(component)) { "Symbolic link in speech package: $path" } }
        val bytes = entry.getLong("bytes")
        require(bytes > 0 && asset.isFile && asset.length() == bytes) { "Speech asset missing or wrong size: $path" }
    }
}
