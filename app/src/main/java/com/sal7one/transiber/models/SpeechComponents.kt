package com.sal7one.transiber.models

import android.content.Context
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.LocalSpeechModel
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.caption.SpeechAbiPolicy
import com.sal7one.transiber.downloads.DownloadAsset
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/** A component set is a package identity, never a pretend single HTTP file. */
internal data class SpeechComponent(val groupId: String, val role: String, val fileName: String,
    val url: String, val bytes: Long, val sha256: String) {
    val id get() = "$groupId-$role"
    fun asset() = DownloadAsset(id, url, bytes, sha256, SpeechComponents.find(groupId)?.bytes ?: bytes)
}
internal data class SpeechComponentGroup(val id: String, val profile: SpeechProfile,
    val revision: String, val parts: List<SpeechComponent>) {
    val bytes get() = parts.sumOf { it.bytes }
    /** SHA-256 of the canonical component inventory; this is explicitly not an archive checksum. */
    val componentSetSha256: String get() = MessageDigest.getInstance("SHA-256").digest(
        parts.sortedBy { it.role }.joinToString("") { "${it.role}\t${it.fileName}\t${it.bytes}\t${it.sha256}\n" }.toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
    init {
        require(parts.size in 2..16 && parts.map { it.role }.toSet().size == parts.size)
        require(parts.map { it.fileName }.toSet().size == parts.size)
        parts.forEach {
            require(it.groupId == id && it.role.matches(Regex("[a-z]+")))
            require(it.fileName.matches(Regex("[A-Za-z0-9._-]+")) && it.fileName != "." && it.fileName != "..")
            require(it.url.startsWith("https://") && it.bytes > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")))
        }
    }
}

internal object SpeechComponents {
    private const val audarId = "audar-asr-v1-flash-q4_k_m"
    private const val revision = "54274d39245beba38f30048128e231d4176bd91d"
    private const val base = "https://huggingface.co/audarai/Audar-ASR-V1-Flash/resolve/$revision/"
    val groups = listOf(SpeechComponentGroup(audarId, SpeechProfile.AUDAR_ASR_V1_FLASH, revision, listOf(
        SpeechComponent(audarId, "model", "Audar-ASR-V1-Flash-Q4_K_M.gguf", base + "Audar-ASR-V1-Flash-Q4_K_M.gguf",
            396700512L, "8b0008ada2f47aaf7c82c10a115f6f964a70ebad71062d491db8c2f6ec8054ed"),
        SpeechComponent(audarId, "encoder", "mmproj-Audar-ASR-V1-Flash.gguf", base + "mmproj-Audar-ASR-V1-Flash.gguf",
            378575424L, "73f06fc82a009b4a9d6c825782a3676cb553402b3a5ecc27ae77a92caa6b7fa9"),
    )))
    fun find(id: String) = groups.firstOrNull { it.id == id }
    fun part(id: String) = groups.asSequence().flatMap { it.parts.asSequence() }.firstOrNull { it.id == id }
    fun forPart(id: String) = groups.firstOrNull { group -> group.parts.any { it.id == id } }

    fun enqueue(context: Context, downloads: FileDownloads, group: SpeechComponentGroup) {
        check(ByokPolicy.FEATURE_BYOK) { "Import models in the offline build" }
        com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "speech model downloads")
        SpeechAbiPolicy.requireSupported(group.profile)
        val existing = downloads.list()
        val transferred = group.parts.sumOf { part -> existing.filter { it.modelId == part.id }.maxOfOrNull { it.bytes.coerceIn(0, part.bytes) } ?: 0L }
        val budget = com.sal7one.transiber.downloads.DownloadBudget.estimate(group.bytes, group.bytes, transferred)
        val required = if (downloads.folderUri != null) budget.privateVolumeRequired else budget.sameVolumeRequired
        val free = com.sal7one.transiber.downloads.DownloadStorage.available(context)
        check(free >= required) { "Not enough device storage for both speech components and their atomic installation: ${required / 1_048_576} MiB required, ${free / 1_048_576} MiB available" }
        group.parts.forEach { downloads.enqueue(DownloadSpec.parse(it.url, it.fileName), true, it.id) }
    }

    /** All source bytes are checked again while staging. Late installation never activates a model. */
    suspend fun install(context: Context, downloads: FileDownloads, group: SpeechComponentGroup): LocalSpeechModel? {
        check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build" }
        com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "speech model installation")
        SpeechAbiPolicy.requireSupported(group.profile)
        val originals = group.parts.associateWith { part -> downloads.list().firstOrNull { row ->
            row.modelId == part.id && downloads.record(row.id)?.optBoolean("downloadComplete") == true
        } ?: return null }
        val job = currentCoroutineContext()
        return LocalSpeechModels(File(context.filesDir, "speech-models")).installComponents(group, { part ->
            context.contentResolver.openInputStream(downloads.uri(originals.getValue(part).id))
                ?: error("Cannot open downloaded ${part.fileName}")
        }) { job.ensureActive() }
    }
}
