package com.sal7one.transiber.benchmark

import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.MarianPackage

/** Maps any catalogue selection to its real downloader records, including shared route files. */
internal data class BenchmarkDownloadProgress(
    val records: List<FileDownload>,
    val expectedFiles: Int,
    val bytes: Long,
    val total: Long?,
) {
    val active get() = records.any(FileDownload::active)
    val paused get() = records.any(FileDownload::paused)
    val failed get() = records.any(FileDownload::failed)
    val complete get() = records.size == expectedFiles && records.all(FileDownload::complete)
    val doneFiles get() = records.count(FileDownload::complete)
    val currentPhase get() = records.firstOrNull { it.phase in setOf("Downloading", "Verifying", "Installing") }?.phase
        ?: records.firstOrNull(FileDownload::active)?.phase
        ?: records.firstOrNull(FileDownload::failed)?.phase
        ?: records.firstOrNull(FileDownload::paused)?.phase
        ?: records.firstOrNull { !it.complete }?.phase
        ?: records.lastOrNull()?.phase.orEmpty()
    val errors get() = records.map(FileDownload::error).filter(String::isNotBlank).distinct()
    val fraction get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }

    companion object {
        fun forModel(model: SuggestedModel, all: List<FileDownload>): BenchmarkDownloadProgress? {
            val ids = when (model.kind) {
                "speech" -> com.sal7one.transiber.models.SpeechComponents.find(model.id)?.parts?.map { it.id } ?: listOf(model.id)
                "marian" -> MarianPackage.find(model.id)?.parts?.map { it.id }
                "cascade" -> MarianCascade.find(model.id)?.let { route ->
                    (route.first.parts + route.second.parts).map { it.id }
                }
                "ct2" -> com.sal7one.common_jni.translation.Ct2Catalog.find(model.id)?.let {
                    com.sal7one.transiber.translation.Ct2Package.parts(it).map { part -> part.id }
                }
                "voice" -> com.sal7one.transiber.voice.VoiceCatalog.familyAssets(model.id).map { it.id }
                "mlkit" -> emptyList()
                else -> listOf(model.id)
            }.orEmpty().distinct()
            if (ids.isEmpty()) return null
            val matching = ids.mapNotNull { id -> all.filter { it.modelId == id }.minByOrNull { it.id } }
            if (matching.isEmpty()) return null
            return BenchmarkDownloadProgress(matching, ids.size,
                matching.sumOf { it.bytes.coerceAtLeast(0) }, model.bytes?.takeIf { it > 0 }
                    ?: matching.map { it.total }.takeIf { it.size == ids.size && it.all { size -> size > 0 } }?.sum())
        }
    }
}
