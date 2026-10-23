package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.transiber.models.PublisherSpeechReceipts
import com.sal7one.transiber.models.SpeechArtifact
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.models.SpeechArtifactKind
import com.sal7one.transiber.models.VoskDownloads
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Catalogue labels reuse the discovered installation inventory, rather than hashing every model.
 * This is installation bookkeeping, not runtime verification. Each selected production runtime
 * still verifies its actual model bytes before loading. Imports remain directly selectable even
 * when they do not have a trusted publisher-install receipt. */
internal object BenchmarkInstalledCatalogue {
    fun map(
        candidates: List<BenchmarkCandidate>,
        speechRoot: File,
        catalog: List<SpeechArtifact> = SpeechArtifactCatalog.all,
    ): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        val speech = candidates.filter { it.task == BenchmarkTask.SPEECH }
        val inventories = mutableMapOf<String, Inventory?>()
        val receipts = PublisherSpeechReceipts(speechRoot)
        for (artifact in catalog) {
            val matched = speech.filter { candidate ->
                try {
                    when (artifact.kind) {
                        SpeechArtifactKind.WHISPER, SpeechArtifactKind.VOSK -> {
                            val route = if (artifact.kind == SpeechArtifactKind.WHISPER) "Whisper /" else "Vosk /"
                            val digest = if (artifact.kind == SpeechArtifactKind.VOSK)
                                VoskDownloads.find(artifact.id)?.treeSha256 ?: artifact.sha256 else artifact.sha256
                            candidate.profile == null && candidate.route.startsWith(route) && candidate.artifactFingerprint == digest &&
                                candidate.file?.let { file -> !Files.isSymbolicLink(file.toPath()) &&
                                    (if (artifact.kind == SpeechArtifactKind.WHISPER) file.isFile else file.isDirectory) } == true
                        }
                        SpeechArtifactKind.PUBLISHER -> {
                            val source = checkNotNull(artifact.publisher)
                            candidate.profile == source.profile && candidate.artifactFingerprint != null &&
                                receipts.matches(candidate.id, source, candidate.artifactFingerprint) &&
                                inventory(candidate, speechRoot, inventories) != null
                        }
                        SpeechArtifactKind.COMPONENTS -> {
                            val group = checkNotNull(artifact.components)
                            val installerId = "speech-${UUID.nameUUIDFromBytes("components-${group.id}".toByteArray(Charsets.UTF_8))}"
                            candidate.id == installerId && candidate.profile == group.profile &&
                                inventory(candidate, speechRoot, inventories)?.let { inventory ->
                                    group.parts.all { part ->
                                        val declared = inventory.files[inventory.roles[part.role]]
                                        declared != null && declared.bytes == part.bytes && declared.sha256 == part.sha256
                                    }
                                } == true
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { false } // A damaged receipt must not hide other installations.
            }.map { it.id }.distinct()
            if (matched.isNotEmpty()) result[artifact.id] = matched
        }
        candidates.filter { it.task == BenchmarkTask.TRANSLATION }.forEach { result[it.id] = listOf(it.id) }
        candidates.filter { it.voiceFamily != null }.groupBy { checkNotNull(it.voiceFamily) }
            .forEach { (family, voices) -> result[family] = voices.map { it.id }.distinct() }
        return result
    }

    private data class Entry(val bytes: Long, val sha256: String)
    private data class Inventory(val roles: Map<String, String>, val files: Map<String, Entry>)

    private fun inventory(candidate: BenchmarkCandidate, speechRoot: File, cache: MutableMap<String, Inventory?>): Inventory? {
        if (cache.containsKey(candidate.id)) return cache[candidate.id]
        val result = try {
            require(candidate.id.matches(Regex("speech-[0-9a-f-]{36}")))
            val root = File(speechRoot, candidate.id)
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
            require(candidate.file?.canonicalFile == root.canonicalFile)
            val manifest = File(root, SpeechModelPackage.MANIFEST)
            require(manifest.isFile && !Files.isSymbolicLink(manifest.toPath()) && manifest.length() in 1..65536)
            val bytes = manifest.readBytes()
            require(benchmarkHash(bytes) == candidate.artifactFingerprint)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("schemaVersion") == 1 && json.getString("profile") == candidate.profile?.id)
            val declared = json.getJSONArray("files")
            require(declared.length() in 1..1000)
            val files = linkedMapOf<String, Entry>()
            for (index in 0 until declared.length()) {
                val entry = declared.getJSONObject(index)
                val path = relative(entry.getString("path"))
                require(path != SpeechModelPackage.MANIFEST && path !in files)
                val size = entry.getLong("bytes")
                val digest = entry.getString("sha256")
                require(size in 1..ModelIntegrity.MAX_SINGLE_FILE_BYTES && digest.matches(Regex("[a-f0-9]{64}")))
                var current = root.toPath()
                path.split('/').forEach { part -> current = current.resolve(part); require(!Files.isSymbolicLink(current)) }
                val file = File(root, path)
                require(file.isFile && file.length() == size)
                files[path] = Entry(size, digest)
            }
            val roles = json.getJSONObject("roles")
            Inventory(roles.keys().asSequence().associateWith { relative(roles.getString(it)) }, files)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        cache[candidate.id] = result
        return result
    }

    private fun relative(path: String): String = path.also {
        require(it.isNotBlank() && it.length <= 1024 && !it.startsWith('/') && '\\' !in it && ':' !in it && '\u0000' !in it)
        require(it.split('/').size <= ModelIntegrity.MAX_RELATIVE_DEPTH && it.split('/').all { part -> part.isNotEmpty() && part != "." && part != ".." })
    }
}
