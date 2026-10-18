package com.sal7one.transiber.models

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.LocalSpeechModel
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.caption.CaptionEngineChoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class SpeechCatalogInstallationsTest {
    @Test fun browserSnapshotKeepsHealthyPackagesAndSurfacesMalformedManifest() = withRoot { root ->
        val healthy = packageAt(root, SpeechProfile.MOONSHINE_TINY_EN)
        val broken = packageAt(root, SpeechProfile.MOONSHINE_TINY_EN)
        File(broken.root, SpeechModelPackage.MANIFEST).writeText("{broken")
        val snapshot = LocalSpeechModels(root).inspect()
        assertEquals(listOf(healthy), snapshot.models)
        assertEquals(1, snapshot.issues.size)
        assertTrue(snapshot.issues.single().contains(broken.id))
        assertTrue(runCatching { LocalSpeechModels(root).list() }.isFailure)
        assertEquals(healthy, LocalSpeechModels(root).select(CaptionEngineChoice.MOONSHINE, healthy.id))
        assertTrue(runCatching { LocalSpeechModels(root).select(CaptionEngineChoice.NEMOTRON, healthy.id) }.isFailure)
        assertTrue(runCatching { LocalSpeechModels(root).select(CaptionEngineChoice.MOONSHINE, "../${healthy.id}") }.isFailure)
    }

    @Test fun customPackageWithSameProfileIsNotThePublisherArtifact() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val custom = packageAt(root, SpeechProfile.MOONSHINE_TINY_EN)
        val snapshot = SpeechCatalogInstallations(root).scan(listOf(artifact), emptyList(), listOf(custom))
        assertTrue(snapshot.installedIds.isEmpty())
        assertTrue(snapshot.issues.isEmpty())
    }

    @Test fun trustedReceiptMatchesOnlyTheExactArchiveAndManifest() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val local = packageAt(root, SpeechProfile.MOONSHINE_TINY_EN)
        PublisherSpeechReceipts(root).record(local.id, checkNotNull(artifact.publisher), SpeechModelPackage.verify(local.root).manifestSha256)
        val resolver = SpeechCatalogInstallations(root)
        assertEquals(local, resolver.scan(listOf(artifact), emptyList(), listOf(local)).publisher[artifact.id])
        assertFalse(resolver.matchesPublisher(local, artifact.publisher.copy(sha256 = "0".repeat(64))))
        File(local.root, SpeechModelPackage.MANIFEST).appendText(" ")
        assertTrue(resolver.scan(listOf(artifact), emptyList(), listOf(local)).installedIds.isEmpty())
    }

    @Test fun corruptCandidateDoesNotHideAnotherVerifiedInstallAndReportsActualError() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val bad = packageAt(root, SpeechProfile.MOONSHINE_TINY_EN)
        File(bad.root, "encoder.ort").writeText("changed")
        val good = packageAt(root, SpeechProfile.MOONSHINE_TINY_EN)
        PublisherSpeechReceipts(root).record(good.id, checkNotNull(artifact.publisher), SpeechModelPackage.verify(good.root).manifestSha256)
        val snapshot = SpeechCatalogInstallations(root).scan(listOf(artifact), emptyList(), listOf(bad, good), bad.id)
        assertEquals(good.id, snapshot.publisher[artifact.id]?.id)
        assertEquals(1, snapshot.issues.size)
        assertTrue(snapshot.issues.single().contains("encoder.ort"))
    }

    @Test fun singleGgufImportRequiresActualPinnedBytesAndInvalidatesOnChange() = withRoot { root ->
        val local = packageAt(root, SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB)
        val model = File(local.root, "model.gguf")
        val source = SpeechDownload(local.profile, "https://example.org/model.gguf", model.length(), sha(model.readBytes()), null, mapOf("model" to "model.gguf"))
        val resolver = SpeechCatalogInstallations(root)
        assertTrue(resolver.matchesPublisher(local, source))
        assertFalse(resolver.matchesPublisher(local, source.copy(sha256 = "0".repeat(64))))
        model.appendText("changed")
        val result = runCatching { resolver.matchesPublisher(local, source) }
        assertTrue(result.isFailure)
    }

    @Test fun registryDigestAloneCannotKeepDeletedOrChangedModelInstalled() = withRoot { root ->
        val file = File(root, "whisper.bin").apply { writeText("fixture") }
        val digest = ModelIntegrity.inspect(file).digest
        val artifact = WhisperDownloads.all.first().copy(id = "fixture-whisper", bytes = file.length(), sha256 = digest.hex)
        val registered = ModelRegistry.RegisteredModel("fixture", "Whisper fixture", ModelEngineType.WHISPER,
            ModelRegistry.ModelSource.INTERNAL, file.path, file.length(), false, true, 0, digest)
        val resolver = SpeechCatalogInstallations(root)
        assertEquals(setOf(artifact.id), resolver.scan(listOf(artifact), listOf(registered), emptyList()).installedIds)
        file.writeText("different")
        val changed = resolver.scan(listOf(artifact), listOf(registered), emptyList())
        assertTrue(changed.installedIds.isEmpty())
        assertEquals(1, changed.issues.size)
        file.delete()
        assertTrue(resolver.scan(listOf(artifact), listOf(registered), emptyList()).installedIds.isEmpty())
    }

    private fun packageAt(root: File, profile: SpeechProfile): LocalSpeechModel {
        val id = "speech-${UUID.randomUUID()}"
        val directory = File(root, id).apply { mkdirs() }
        val roles = if (profile == SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB) mapOf("model" to "model.gguf")
            else mapOf("model" to "tokens.txt", "encoder" to "encoder.ort", "decoder" to "decoder.ort")
        val entries = JSONArray()
        roles.values.forEach { name ->
            val bytes = (if (name.endsWith("gguf")) "GGUFfixture" else "fixture-$name").toByteArray()
            File(directory, name).writeBytes(bytes)
            entries.put(JSONObject().put("path", name).put("bytes", bytes.size).put("sha256", sha(bytes)))
        }
        File(directory, SpeechModelPackage.MANIFEST).writeText(JSONObject().put("schemaVersion", 1)
            .put("profile", profile.id).put("roles", JSONObject(roles)).put("files", entries).toString())
        return LocalSpeechModel(id, profile, directory)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("speech-catalog-installations").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
