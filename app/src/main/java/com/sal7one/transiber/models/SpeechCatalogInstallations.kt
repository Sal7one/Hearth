package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.transiber.caption.LocalSpeechModel
import com.sal7one.transiber.setup.SetupModelVerifier
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

internal data class SpeechCatalogInstallationSnapshot(
    val registered: Map<String, ModelRegistry.RegisteredModel>,
    val publisher: Map<String, LocalSpeechModel>,
    val issues: List<String>,
) {
    val installedIds: Set<String> get() = registered.keys + publisher.keys
}

/** IO only. A compatible profile is not proof that a catalog artifact is installed. */
internal class SpeechCatalogInstallations(
    private val modelRoot: File,
    private val verifier: SetupModelVerifier = SetupModelVerifier(),
) {
    fun scan(
        catalog: List<SpeechArtifact>,
        registered: List<ModelRegistry.RegisteredModel>,
        publisher: List<LocalSpeechModel>,
        selectedId: String = "",
        fullIntegrity: Boolean = false,
    ): SpeechCatalogInstallationSnapshot {
        val registryMatches = linkedMapOf<String, ModelRegistry.RegisteredModel>()
        val publisherMatches = linkedMapOf<String, LocalSpeechModel>()
        val issues = linkedSetOf<String>()
        val preferredRegistry = registered.sortedBy { it.id != selectedId }
        val preferredPublisher = publisher.sortedBy { it.id != selectedId }
        catalog.forEach { artifact ->
            if (artifact.kind == SpeechArtifactKind.COMPONENTS) {
                val source = checkNotNull(artifact.components)
                preferredPublisher.filter { it.profile == source.profile }.firstOrNull { model ->
                    checked(model.id, issues) { matchesComponents(model, source, fullIntegrity) }
                }?.let { publisherMatches[artifact.id] = it }
            } else if (artifact.kind == SpeechArtifactKind.PUBLISHER) {
                val source = checkNotNull(artifact.publisher)
                preferredPublisher.filter { it.profile == source.profile }.firstOrNull { model ->
                    checked(model.id, issues) { matchesPublisher(model, source, fullIntegrity) }
                }?.let { publisherMatches[artifact.id] = it }
            } else {
                val type = if (artifact.kind == SpeechArtifactKind.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK
                val digest = VoskDownloads.find(artifact.id)?.treeSha256 ?: artifact.sha256
                preferredRegistry.filter { it.isValid && it.engineType == type && it.digest?.hex == digest }
                    .firstOrNull { model -> checked(model.name, issues) {
                        verifier.inspect(File(model.path), digest, fullIntegrity)
                        true
                    } }?.let { registryMatches[artifact.id] = it }
            }
        }
        return SpeechCatalogInstallationSnapshot(registryMatches, publisherMatches, issues.toList())
    }

    fun matchesPublisher(model: LocalSpeechModel, source: SpeechDownload, fullIntegrity: Boolean = false): Boolean {
        if (model.profile != source.profile) return false
        val verified = verifier.speech(model.root, fullIntegrity)
        if (verified.profile != source.profile) return false
        if (source.archiveRoot != null) {
            return PublisherSpeechReceipts(modelRoot).matches(model.id, source, verified.manifestSha256)
        }
        // Single GGUF publishers can also be recognized after a custom ZIP import,
        // but only when the actual role bytes match the pinned publisher artifact.
        val manifest = JSONObject(File(model.root, SpeechModelPackage.MANIFEST).readText())
        val file = File(model.root, manifest.getJSONObject("roles").getString("model"))
        return file.length() == source.bytes && verifier.inspect(file, force = fullIntegrity).digest.hex == source.sha256
    }

    /** Match every role's actual bytes, including imports; a decoder hash cannot identify the complete model. */
    fun matchesComponents(model: LocalSpeechModel, source: SpeechComponentGroup, fullIntegrity: Boolean = false): Boolean {
        if (model.profile != source.profile) return false
        val verified = verifier.speech(model.root, fullIntegrity)
        if (verified.profile != source.profile) return false
        val roles = JSONObject(File(model.root, SpeechModelPackage.MANIFEST).readText()).getJSONObject("roles")
        return source.parts.all { part ->
            val file = File(model.root, roles.getString(part.role))
            file.length() == part.bytes && verifier.inspect(file, force = fullIntegrity).digest.hex == part.sha256
        }
    }

    private inline fun checked(name: String, issues: MutableSet<String>, operation: () -> Boolean): Boolean = try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        issues += "$name: ${e.message ?: e.toString()}"
        false
    }
}
