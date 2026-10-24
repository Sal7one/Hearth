package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.LocalSpeechModels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Opt-in installer coverage with the actual converted weights; ordinary host tests skip it. */
class Phonon25InstallerRealSmokeTest {
    private data class Pin(val bytes: Long, val sha256: String)

    private val roles = mapOf(
        "model" to "tokens.txt", "frontend" to "joiner.int8.onnx",
        "encoder" to "encoder.int8.onnx", "decoder" to "decoder.int8.onnx",
    )
    // Reviewed conversion of source revision 96dad4f3b5b784b1aa595213ea7481bf8a7addcd.
    // Pin attribution assets too, so a successful import cannot silently discard them.
    private val assets = mapOf(
        "tokens.txt" to Pin(93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        "encoder.int8.onnx" to Pin(611893971, "2e644946846e0027c1c8973ab88675643e17720ac6fdb15320365e040519d63a"),
        "decoder.int8.onnx" to Pin(12257658, "9759e3eff1ccb5b79c694f218377a780a327ffc693a0fb21db812bd2754ceaac"),
        "joiner.int8.onnx" to Pin(5281492, "b848103db33b89be7167d218587ea59e772e40ba6ba1cd787230e7e754839633"),
        "LICENSE-WEIGHTS-CC-BY-4.0.txt" to Pin(18657, "9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411"),
        "NOTICE" to Pin(2664, "dd4993badd45eb336280a0ab5b4ecb3ed06260d189a30fcae8b9e89a587c4de8"),
    )

    @Test fun actualPublisherTarAndOfflineZipInstallTheSamePinnedMultilingualPackage() {
        assumeTrue("Set HEARTH_PHONON_25_INSTALL_SMOKE=1 to validate real installer artifacts",
            System.getenv("HEARTH_PHONON_25_INSTALL_SMOKE") == "1")
        val archives = File(requireNotNull(System.getenv("HEARTH_PHONON_25_ARCHIVE_DIR")) {
            "HEARTH_PHONON_25_ARCHIVE_DIR must contain the final Phonon 2.5 TAR and ZIP"
        }).canonicalFile
        require(archives.isDirectory) { "Phonon 2.5 artifact directory is unavailable: $archives" }
        val source = requireNotNull(SpeechDownloads.find(SpeechProfile.PHONON_2_5.id)) {
            "The actual Phonon 2.5 publisher archive must be pinned in SpeechDownloads"
        }
        assertEquals(SpeechProfile.PHONON_2_5, source.profile)
        assertEquals(roles, source.roles)
        require(!source.archiveRoot.isNullOrBlank()) { "The Phonon 2.5 publisher route must use a named-root TAR" }
        val tar = archives.resolve(source.fileName)
        val zip = archives.resolve("phonon-2.5-hearth-int8.zip")
        require(tar.isFile && zip.isFile) { "Missing final installer archives: ${tar.name}, ${zip.name}" }
        assertEquals("Publisher TAR must match the actual download size", source.bytes, tar.length())

        val work = Files.createTempDirectory("hearth-phonon25-installer-").toFile()
        try {
            val publisherWork = work.resolve("publisher").apply { check(mkdir()) }
            // stage verifies the catalog archive size/SHA and rejects supplied hearth-speech.json.
            val staged = tar.inputStream().use { PublisherSpeechPackage.stage(it, source, publisherWork) }
            assertPackage(staged)

            // This is the same flat-root, manifest-bearing ZIP importer offered in Android Models.
            val imported = zip.inputStream().use { LocalSpeechModels(work.resolve("imports")).importZip(it) }
            assertEquals(SpeechProfile.PHONON_2_5, imported.profile)
            assertPackage(imported.root)
            println("PHONON_25_INSTALL_SMOKE publisher=${tar.name} bytes=${tar.length()} sha256=${source.sha256} " +
                "offline=${zip.name} bytes=${zip.length()} profile=${imported.profile.id} assets=${assets.size} roles=${roles.size}")
        } finally { assertTrue("Failed to remove real installer smoke staging", work.deleteRecursively()) }
    }

    private fun assertPackage(root: File) {
        // Full production validation rehashes every declared file, rejects extras, and checks roles.
        val verified = SpeechModelPackage.verify(root)
        assertEquals(SpeechProfile.PHONON_2_5, verified.profile)
        assertEquals(assets.values.sumOf { it.bytes }, verified.sizeBytes)
        assertEquals(assets.keys + SpeechModelPackage.MANIFEST, root.listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(root.listFiles().orEmpty().all { it.isFile })
        val manifest = JSONObject(root.resolve(SpeechModelPackage.MANIFEST).readText())
        assertEquals(1, manifest.getInt("schemaVersion"))
        assertEquals(SpeechProfile.PHONON_2_5.id, manifest.getString("profile"))
        val actualRoles = manifest.getJSONObject("roles").let { json ->
            json.keys().asSequence().associateWith { json.getString(it) }
        }
        assertEquals(roles, actualRoles)
        val files = manifest.getJSONArray("files")
        val actualAssets = (0 until files.length()).associate { index ->
            files.getJSONObject(index).let { entry -> entry.getString("path") to Pin(entry.getLong("bytes"), entry.getString("sha256")) }
        }
        assertEquals(assets.size, files.length())
        assertEquals(assets, actualAssets)
        val notice = root.resolve("NOTICE").readText()
        assertTrue(notice.contains("Phonon-2.5") && notice.contains("Fermion Research"))
        assertTrue(root.resolve("LICENSE-WEIGHTS-CC-BY-4.0.txt").readText().contains("Attribution 4.0 International"))
    }
}
