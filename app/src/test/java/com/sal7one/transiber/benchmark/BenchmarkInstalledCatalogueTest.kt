package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.models.PublisherSpeechReceipts
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.models.SpeechComponent
import com.sal7one.transiber.models.SpeechComponentGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class BenchmarkInstalledCatalogueTest {
    @Test fun verifiedPublisherReceiptMapsOnlyItsExactSourceAndCurrentManifest() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val source = checkNotNull(artifact.publisher)
        val candidate = packageAt(root, source.profile)
        val receipts = PublisherSpeechReceipts(root)
        receipts.record(candidate.id, source, checkNotNull(candidate.artifactFingerprint))
        assertEquals(listOf(candidate.id), BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact))[artifact.id])
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(candidate.copy(profile = SpeechProfile.MOONSHINE_BASE_EN)), root, listOf(artifact)).isEmpty())
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact.copy(publisher = source.copy(sha256 = "0".repeat(64))))).isEmpty())
        File(candidate.file, SpeechModelPackage.MANIFEST).appendText(" ")
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact)).isEmpty())
    }

    @Test fun anImportedCompatibleProfileCannotImpersonateAnOfficialPublisherInstallation() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val imported = packageAt(root, checkNotNull(artifact.profile))
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(imported), root, listOf(artifact)).isEmpty())
        // It remains a normal candidate; absence of a catalogue badge does not remove it.
        assertEquals(BenchmarkTask.SPEECH, imported.task)
    }

    @Test fun missingAndTruncatedAssetsAreNotMappedDespiteTheOldInstallationReceipt() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val candidate = packageAt(root, checkNotNull(artifact.profile))
        PublisherSpeechReceipts(root).record(candidate.id, checkNotNull(artifact.publisher), checkNotNull(candidate.artifactFingerprint))
        val file = File(candidate.file, "encoder.ort")
        file.writeText("x")
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact)).isEmpty())
        file.delete()
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact)).isEmpty())
    }

    @Test fun installedMappingDoesNotRehashWeightsAndRuntimeStillRejectsTheirCorruption() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val candidate = packageAt(root, checkNotNull(artifact.profile))
        PublisherSpeechReceipts(root).record(candidate.id, checkNotNull(artifact.publisher), checkNotNull(candidate.artifactFingerprint))
        val file = File(candidate.file, "encoder.ort")
        file.writeBytes(ByteArray(file.length().toInt()) { 42 })
        assertEquals(listOf(candidate.id), BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact))[artifact.id])
        assertTrue("The inference boundary must still detect same-size damaged weights",
            runCatching { SpeechModelPackage.verify(checkNotNull(candidate.file)) }.isFailure)
    }

    @Test fun nestedSymlinksCannotBeUsedAsInstalledAssets() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val candidate = packageAt(root, checkNotNull(artifact.profile))
        PublisherSpeechReceipts(root).record(candidate.id, checkNotNull(artifact.publisher), checkNotNull(candidate.artifactFingerprint))
        val file = File(candidate.file, "encoder.ort")
        val other = File(root, "outside.ort").apply { writeBytes(file.readBytes()) }
        file.delete()
        Files.createSymbolicLink(file.toPath(), other.toPath())
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(candidate), root, listOf(artifact)).isEmpty())
    }

    @Test fun componentPackagesRequireInstallerIdentityAndEveryRolePin() = withRoot { root ->
        val base = checkNotNull(SpeechArtifactCatalog.find("audar-asr-v1-flash-q4_k_m"))
        val profile = checkNotNull(base.profile)
        val fixtureBytes = "GGUFfixture".toByteArray()
        val group = SpeechComponentGroup("fixture-audar", profile, "fixture", listOf("model", "encoder").map { role ->
            SpeechComponent("fixture-audar", role, "$role.gguf", "https://example.org/$role.gguf", fixtureBytes.size.toLong(), benchmarkHash(fixtureBytes))
        })
        val artifact = base.copy(id = group.id, bytes = group.bytes, sha256 = group.componentSetSha256, components = group)
        val installerId = "speech-${UUID.nameUUIDFromBytes("components-${group.id}".toByteArray(Charsets.UTF_8))}"
        val official = packageAt(root, profile, installerId)
        val imported = packageAt(root, profile)
        assertEquals(listOf(official.id), BenchmarkInstalledCatalogue.map(listOf(imported, official), root, listOf(artifact))[artifact.id])
        val changed = group.copy(parts = group.parts.map { it.copy(sha256 = "0".repeat(64)) })
        assertTrue(BenchmarkInstalledCatalogue.map(listOf(official), root,
            listOf(artifact.copy(components = changed, sha256 = changed.componentSetSha256))).isEmpty())
    }

    @Test fun legacyPinsAndDirectTranslationAndVoiceCandidatesReuseTheDiscoveredInventory() = withRoot { root ->
        val artifact = SpeechArtifactCatalog.all.first { it.kind.name == "WHISPER" }
        val file = File(root, "whisper.bin").apply { writeText("fixture") }
        val correct = BenchmarkCandidate("legacy-import", "Whisper", "Whisper / batch", setOf("en"), file = file, artifactFingerprint = artifact.sha256)
        val wrong = correct.copy(id = "wrong-pin", artifactFingerprint = "0".repeat(64))
        val wrongEngine = correct.copy(id = "wrong-engine", route = "Vosk / batch")
        val translation = BenchmarkCandidate("ct2-fixture", "CT2", "Translation / CT2", setOf("en"), setOf("ar"))
        val voices = List(100) { index -> BenchmarkCandidate("voice-$index", "Voice $index", "Voice / CPU", setOf("en"),
            voiceFamily = "fixture-voice", voiceId = index.toString()) }
        val mapped = BenchmarkInstalledCatalogue.map(listOf(correct, wrong, wrongEngine, translation) + voices, root, listOf(artifact))
        assertEquals(listOf(correct.id), mapped[artifact.id])
        assertEquals(listOf(translation.id), mapped[translation.id])
        assertEquals(voices.map { it.id }, mapped["fixture-voice"])
    }

    @Test fun oneMalformedReceiptDoesNotHideOtherModels() = withRoot { root ->
        val artifact = checkNotNull(SpeechArtifactCatalog.find("moonshine-tiny-en-v2"))
        val bad = packageAt(root, checkNotNull(artifact.profile))
        val good = packageAt(root, checkNotNull(artifact.profile))
        PublisherSpeechReceipts(root).record(good.id, checkNotNull(artifact.publisher), checkNotNull(good.artifactFingerprint))
        File(root, ".publisher-receipts/${bad.id}.json").writeText("{broken")
        assertEquals(listOf(good.id), BenchmarkInstalledCatalogue.map(listOf(bad, good), root, listOf(artifact))[artifact.id])
    }

    private fun packageAt(root: File, profile: SpeechProfile, id: String = "speech-${UUID.randomUUID()}"): BenchmarkCandidate {
        val directory = File(root, id).apply { mkdirs() }
        val roles = if (profile == SpeechProfile.AUDAR_ASR_V1_FLASH) mapOf("model" to "model.gguf", "encoder" to "encoder.gguf")
            else mapOf("model" to "tokens.txt", "encoder" to "encoder.ort", "decoder" to "decoder.ort")
        val entries = JSONArray()
        roles.values.forEach { name ->
            val bytes = (if (name.endsWith("gguf")) "GGUFfixture" else "fixture-$name").toByteArray()
            File(directory, name).writeBytes(bytes)
            entries.put(JSONObject().put("path", name).put("bytes", bytes.size).put("sha256", benchmarkHash(bytes)))
        }
        val bytes = JSONObject().put("schemaVersion", 1).put("profile", profile.id)
            .put("roles", JSONObject(roles)).put("files", entries).toString().toByteArray()
        File(directory, SpeechModelPackage.MANIFEST).writeBytes(bytes)
        return BenchmarkCandidate(id, "Fixture", "Speech / batch", setOf("en"), file = directory, profile = profile,
            artifactFingerprint = benchmarkHash(bytes))
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("benchmark-installed-catalogue").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
