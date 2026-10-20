package com.sal7one.transiber.models

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Stages a complete pinned component set under the ordinary strict speech package verifier. */
internal object SpeechComponentPackage {
    fun stage(source: SpeechComponentGroup, work: File, open: (SpeechComponent) -> InputStream,
        checkActive: () -> Unit = {}): File {
        val staged = try { ModelIntegrity.stageDirectory(work, "package") { sink ->
            val files = JSONArray()
            source.parts.forEach { part ->
                checkActive()
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                open(part).use { input ->
                    val bounded = object : FilterInputStream(DigestInputStream(input, digest)) {
                        override fun read(): Int { checkActive(); val b = `in`.read(); if (b >= 0) account(1); return b }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { checkActive(); val n = `in`.read(b, off, len); if (n > 0) account(n); return n }
                        private fun account(n: Int) { count += n; require(count <= part.bytes) { "${part.fileName} exceeds its pinned size" } }
                    }
                    sink.addFile(part.fileName, bounded)
                }
                require(count == part.bytes) { "${part.fileName} is truncated: $count, expected ${part.bytes}" }
                require(digest.digest().joinToString("") { "%02x".format(it) } == part.sha256) { "${part.fileName} SHA-256 mismatch" }
                files.put(JSONObject().put("path", part.fileName).put("bytes", part.bytes).put("sha256", part.sha256))
            }
            val manifest = JSONObject().put("schemaVersion", 1).put("profile", source.profile.id)
                .put("roles", JSONObject(source.parts.associate { it.role to it.fileName })).put("files", files)
            sink.addFile(SpeechModelPackage.MANIFEST, manifest.toString().byteInputStream())
        } } catch (e: com.sal7one.common_jni.model.ModelIntegrityException) {
            // The generic staging helper wraps callbacks. Preserve cancellation
            // and the real per-component checksum/read failure for the queue UI.
            val cause = e.cause
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            if (cause != null) throw com.sal7one.common_jni.model.ModelIntegrityException(cause.message ?: cause.toString(), e)
            throw e
        }
        try { checkActive(); SpeechModelPackage.verify(staged.file); return staged.file }
        catch (e: Exception) { staged.file.deleteRecursively(); throw e }
    }
}
