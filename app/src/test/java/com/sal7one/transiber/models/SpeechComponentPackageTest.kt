package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.LocalSpeechModel
import com.sal7one.transiber.downloads.DownloadAssets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class SpeechComponentPackageTest {
    private val payloads = mapOf("model" to "GGUFdecoder".toByteArray(), "encoder" to "GGUFencoder".toByteArray())
    private fun group() = SpeechComponentGroup("test-audar", SpeechProfile.AUDAR_ASR_V1_FLASH, "revision",
        payloads.map { (role, bytes) -> SpeechComponent("test-audar", role, "$role.gguf", "https://example.org/$role.gguf",
            bytes.size.toLong(), sha(bytes)) })

    @Test fun onlyRealComponentsAreTransportAssetsAndTotalIsTheSumOfBothPins() {
        val source = SpeechComponents.groups.single()
        assertEquals(775275936L, source.bytes)
        assertNull(DownloadAssets.find(source.id))
        source.parts.forEach { part ->
            val asset = checkNotNull(DownloadAssets.find(part.id))
            assertEquals(part.bytes, asset.bytes)
            assertEquals(part.sha256, asset.sha256)
            assertEquals(source.bytes, asset.installedBytes)
        }
        assertNotEquals(source.parts.first().sha256, source.componentSetSha256)
        assertEquals(source.componentSetSha256, source.copy(parts = source.parts.reversed()).componentSetSha256)
    }

    @Test fun completeSetCreatesAnOrdinaryStrictSpeechPackageAndMatchesBothRoles() = withWork { work ->
        val source = group()
        val staged = SpeechComponentPackage.stage(source, work, { payloads.getValue(it.role).inputStream() })
        val verified = SpeechModelPackage.verify(staged)
        assertEquals(SpeechProfile.AUDAR_ASR_V1_FLASH, verified.profile)
        assertEquals(setOf("model", "encoder"), JSONObject(File(staged, SpeechModelPackage.MANIFEST).readText())
            .getJSONObject("roles").keys().asSequence().toSet())
        val model = LocalSpeechModel("speech-${UUID.randomUUID()}", verified.profile, staged)
        val scanner = SpeechCatalogInstallations(work)
        assertTrue(scanner.matchesComponents(model, source))
        assertFalse(scanner.matchesComponents(model, source.copy(parts = source.parts.map {
            if (it.role == "encoder") it.copy(sha256 = "0".repeat(64)) else it
        })))
        // An imported receipt-like file cannot replace the actual component bytes.
        File(staged, "fake-receipt.json").writeText("{\"componentSetSha256\":\"${source.componentSetSha256}\"}")
        assertTrue(runCatching { scanner.matchesComponents(model, source, true) }.isFailure)
    }

    @Test fun missingSwappedCorruptOversizedAndCancelledInputsNeverPublishAPackage() {
        for (case in listOf("missing", "swapped", "corrupt", "oversized", "cancelled")) withWork { work ->
            val failure = runCatching { SpeechComponentPackage.stage(group(), work, { part ->
                when (case) {
                    "missing" -> if (part.role == "encoder") error("Missing encoder") else payloads.getValue(part.role).inputStream()
                    "swapped" -> payloads.getValue(if (part.role == "model") "encoder" else "model").inputStream()
                    "corrupt" -> "GGUFinvalid".byteInputStream()
                    "oversized" -> (payloads.getValue(part.role) + byteArrayOf(1)).inputStream()
                    else -> payloads.getValue(part.role).inputStream()
                }
            }, { if (case == "cancelled") throw java.util.concurrent.CancellationException("cancelled") }) }
            assertTrue(case, failure.isFailure)
            assertFalse(case, File(work, "package").exists())
            if (case == "cancelled") assertTrue(failure.exceptionOrNull() is java.util.concurrent.CancellationException)
            if (case == "corrupt") assertTrue(failure.exceptionOrNull()!!.message.orEmpty().contains("SHA-256 mismatch"))
        }
    }

    @Test fun decoderOnlyManifestCannotMasqueradeAsAudar() = withWork { work ->
        val source = group()
        val staged = SpeechComponentPackage.stage(source, work, { payloads.getValue(it.role).inputStream() })
        val manifestFile = File(staged, SpeechModelPackage.MANIFEST)
        val manifest = JSONObject(manifestFile.readText())
        manifest.getJSONObject("roles").remove("encoder")
        manifestFile.writeText(manifest.toString())
        assertTrue(runCatching { SpeechModelPackage.verify(staged) }.isFailure)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private inline fun withWork(block: (File) -> Unit) {
        val work = Files.createTempDirectory("speech-components").toFile()
        try { block(work) } finally { work.deleteRecursively() }
    }
}
