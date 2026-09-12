package com.sal7one.transiber.caption

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream

internal val CaptionEngineChoice.speechBackend: SpeechBackend? get() = when (this) {
    CaptionEngineChoice.MOONSHINE -> SpeechBackend.MOONSHINE
    CaptionEngineChoice.QWEN -> SpeechBackend.QWEN3_ASR
    CaptionEngineChoice.NEMOTRON -> SpeechBackend.NEMOTRON_3_5
    else -> null
}
internal val SpeechProfile.captionEngine: CaptionEngineChoice get() = when (backend) {
    SpeechBackend.MOONSHINE -> CaptionEngineChoice.MOONSHINE
    SpeechBackend.QWEN3_ASR -> CaptionEngineChoice.QWEN
    SpeechBackend.NEMOTRON_3_5 -> CaptionEngineChoice.NEMOTRON
}
internal val SpeechProfile.label: String get() = when (this) {
    SpeechProfile.MOONSHINE_TINY_EN -> "Moonshine Tiny · English"
    SpeechProfile.MOONSHINE_BASE_EN -> "Moonshine Base · English"
    SpeechProfile.QWEN3_ASR_0_6B -> "Qwen3-ASR 0.6B"
    SpeechProfile.QWEN3_ASR_1_7B -> "Qwen3-ASR 1.7B (larger; phone performance unverified)"
    SpeechProfile.NEMOTRON_3_5_ASR_0_6B -> "Nemotron 3.5 ASR 0.6B"
}
internal data class LocalSpeechModel(val id: String, val profile: SpeechProfile, val root: File)

/** Owns immutable app-private installs; all filesystem work belongs on an IO dispatcher. */
internal class LocalSpeechModels(private val root: File) {
    fun list(): List<LocalSpeechModel> = root.listFiles().orEmpty()
        .filter { it.isDirectory && validId(it.name) }
        .sortedBy { it.name }.map { directory ->
            val manifest = File(directory, SpeechModelPackage.MANIFEST)
            require(manifest.length() in 1..65536) { "Missing or oversized ${manifest.path}" }
            val profile = SpeechProfile.fromId(JSONObject(manifest.readText()).getString("profile"))
            LocalSpeechModel(directory.name, profile, directory)
        }

    fun select(engine: CaptionEngineChoice, id: String): LocalSpeechModel {
        val candidates = list().filter { it.profile.backend == engine.speechBackend }
        return if (id.isBlank()) candidates.firstOrNull()
            ?: error("No ${engine.label} model installed. Open Models to download or import it.")
        else candidates.firstOrNull { it.id == id }
            ?: error("Selected ${engine.label} package is missing: $id. Select an imported model again.")
    }

    /** ZIP contents must have hearth-speech.json at the root. No temporary extracted tree is selectable. */
    fun importZip(input: InputStream, checkActive: () -> Unit = {}): LocalSpeechModel {
        val stagingRoot = File(root, ".staging").apply { mkdirs() }
        val workRoot = File(stagingRoot, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            val staged = ModelIntegrity.stageDirectory(workRoot, "package") { sink ->
                ZipInputStream(input).use { zip ->
                    var entries = 0
                    while (true) {
                        checkActive()
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 2000) { "Speech archive exceeds 2000 entries" }
                        if (entry.isDirectory) sink.addDirectory(entry.name.trimEnd('/'))
                        else sink.addFile(entry.name, object : java.io.FilterInputStream(zip) {
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                checkActive(); return super.read(b, off, len)
                            }
                        })
                        zip.closeEntry()
                    }
                }
            }
            checkActive()
            val verified = SpeechModelPackage.verify(staged.file)
            checkActive()
            val id = "speech-${UUID.randomUUID()}"
            val destination = File(root, id)
            Files.move(staged.file.toPath(), destination.toPath())
            return LocalSpeechModel(id, verified.profile, destination)
        } finally { workRoot.deleteRecursively() }
    }

    fun installPublisher(input: InputStream, source: com.sal7one.transiber.models.SpeechDownload, downloadId: Long,
        checkActive: () -> Unit = {}): LocalSpeechModel {
        val id = "speech-${UUID.nameUUIDFromBytes("download-$downloadId".toByteArray())}"
        val destination = File(root, id)
        if (destination.isDirectory) {
            val verified = SpeechModelPackage.verify(destination)
            require(verified.profile == source.profile) { "Installed download profile differs" }
            return LocalSpeechModel(id, verified.profile, destination)
        }
        // Reuse the job's staging path so a killed process cannot leave a second
        // partially extracted model behind when that download is retried.
        val work = File(root, ".staging/publisher-$downloadId")
        check(!work.exists() || work.deleteRecursively()) { "Cannot clear interrupted model installation" }
        check(work.mkdirs()) { "Cannot create model installation staging directory" }
        try {
            val staged = com.sal7one.transiber.models.PublisherSpeechPackage.stage(input, source, work, checkActive)
            checkActive()
            Files.move(staged.toPath(), destination.toPath())
            return LocalSpeechModel(id, source.profile, destination)
        } finally { work.deleteRecursively() }
    }

    private fun validId(id: String) = id.matches(Regex("speech-[0-9a-f-]{36}"))
}
