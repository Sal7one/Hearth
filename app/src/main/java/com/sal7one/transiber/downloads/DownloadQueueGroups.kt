package com.sal7one.transiber.downloads

import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.translation.MarianPackage

/** Package components share one queue action; individual copies remain available in file details. */
internal data class DownloadQueueGroup(val key: String, val modelId: String?, val rows: List<FileDownload>,
    val required: Map<String, Long>) {
    val voiceFamily get() = key.takeIf { it.startsWith("voice:") }?.removePrefix("voice:")
    val multipart get() = required.size > 1
    val representatives: List<FileDownload> get() = rows.groupBy { it.modelId }.values.map { copies ->
        copies.minWith(compareBy<FileDownload> { when { it.active -> 0; it.installed -> 1; it.complete -> 2; it.paused -> 3; else -> 4 } }.thenBy { it.id })
    }
    val completed get() = representatives.count { it.modelId in required && it.complete }
    val active get() = representatives.any { it.active }
    val installedRecord get() = representatives.firstOrNull { it.installed }?.takeIf {
        completed == required.size && (voiceFamily == null || representatives.all { row -> row.installed }) }
    val failure get() = representatives.firstOrNull { it.failed && !it.complete }
    val stage: DownloadStage? get() = when {
        active -> representatives.filter { it.active }.minBy { row -> when (row.displayStage()) {
            DownloadStage.INSTALLING -> 0; DownloadStage.VERIFYING -> 1; DownloadStage.TRANSFERRING -> 2; else -> 3
        } }.displayStage()
        failure != null -> DownloadStage.FAILED
        representatives.any { it.paused } -> DownloadStage.PAUSED
        installedRecord != null -> DownloadStage.INSTALLED
        completed == required.size && required.isNotEmpty() -> DownloadStage.READY
        else -> null
    }
    val priority get() = when (stage) {
        DownloadStage.WAITING, DownloadStage.TRANSFERRING, DownloadStage.VERIFYING, DownloadStage.INSTALLING -> 0
        DownloadStage.FAILED, DownloadStage.PAUSED -> 1
        DownloadStage.INSTALLED -> 3
        else -> 2
    }
    val transferred get() = representatives.sumOf { row -> required[row.modelId]?.let { size -> if (row.complete) size else row.bytes.coerceIn(0, size) } ?: 0 }
    val total get() = required.values.sum()
    val fraction: Float? get() = if (stage == DownloadStage.TRANSFERRING && total > 0) (transferred.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
}

internal fun downloadQueueGroups(rows: List<FileDownload>): List<DownloadQueueGroup> = rows.groupBy { row ->
    com.sal7one.transiber.voice.VoiceCatalog.find(row.modelId)?.let { "voice:${it.family}" }
        ?: com.sal7one.transiber.models.SpeechComponents.forPart(row.modelId)?.id ?: MarianPackage.part(row.modelId)?.pairId ?: Ct2Package.part(row.modelId)?.spec?.id ?: "file:${row.id}"
}.map { (key, copies) ->
    val pair = MarianPackage.find(key)
    val ct2 = com.sal7one.common_jni.translation.Ct2Catalog.find(key)
    val voice = key.takeIf { it.startsWith("voice:") }?.removePrefix("voice:")
    val voiceParts = voice?.let { family -> com.sal7one.transiber.voice.VoiceCatalog.familyAssets(family).filter { asset ->
        family != "supertonic" || asset in com.sal7one.transiber.voice.VoiceCatalog.core || copies.any { it.modelId == asset.id }
    }.associate { it.id to it.bytes } }
    val parts = voiceParts ?: com.sal7one.transiber.models.SpeechComponents.find(key)?.parts?.associate { it.id to it.bytes } ?: pair?.parts?.associate { it.id to it.bytes } ?: ct2?.let { Ct2Package.parts(it).associate { part -> part.id to part.file.bytes } }
    DownloadQueueGroup(key, key.takeIf { parts != null }, copies, parts ?: mapOf(copies.first().modelId to copies.first().total.coerceAtLeast(copies.first().bytes).coerceAtLeast(0)))
}
