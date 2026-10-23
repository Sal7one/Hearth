package com.sal7one.transiber.settings

import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.common_jni.translation.TranslationModelSpec
import com.sal7one.transiber.models.ModelSearch
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.downloads.*
import java.io.File

/** Checkpoints are cards; precision changes do not create another card or select a runtime. */
internal data class TranslationBrowserItem(
    val id: String,
    val title: String,
    val variants: List<TranslationModelSpec> = emptyList(),
    val pair: MarianPackage.Pair? = null,
    val route: MarianCascade.Route? = null,
) {
    val sourceLanguages: Set<String> get() = variants.firstOrNull()?.sourceLanguages
        ?: setOf(pair?.source ?: checkNotNull(route).source)
    val targetLanguages: Set<String> get() = variants.firstOrNull()?.targetLanguages
        ?: setOf(pair?.target ?: checkNotNull(route).target)
    val compact: Boolean get() = variants.isEmpty()
    val throughEnglish: Boolean get() = route != null
    fun installed(installedIds: Set<String>): Boolean = if (variants.isEmpty()) id in installedIds
        else variants.any { it.id in installedIds }
    fun matches(query: String, source: String, target: String, kind: String): Boolean {
        if (kind == "compact" && !compact || kind == "text" && compact) return false
        if (source != "any" && source !in sourceLanguages) return false
        if (target != "any" && target !in targetLanguages) return false
        if (source != "any" && source == target) return false
        if (source != "any" && target != "any" && variants.isNotEmpty() && variants.none { it.supports(source, target) }) return false
        return if (variants.isNotEmpty()) variants.any { variantMatches(query, it) }
        else ModelSearch.matches(query, title, id, "Marian OPUS-MT ONNX CPU compact",
            ModelSearch.languageTerms(sourceLanguages + targetLanguages),
            if (throughEnglish) "English pivot route two models" else "direct",
            pair?.license.orEmpty(), pair?.repository.orEmpty(),
            route?.let { "${it.first.repository} ${it.second.repository} ${it.first.license} ${it.second.license}" }.orEmpty())
    }

    fun matchingVariants(query: String, installedIds: Set<String>? = null): List<TranslationModelSpec> =
        variants.filter { variantMatches(query, it) && (installedIds == null || it.id in installedIds) }

    private fun variantMatches(query: String, variant: TranslationModelSpec): Boolean = ModelSearch.matches(
        query, title, id, variant.id, variant.family, variant.quantization, variant.license,
        variant.repo, variant.fileName, "GGUF multilingual text CPU GPU Vulkan OpenCL Adreno", ModelSearch.languageTerms(sourceLanguages + targetLanguages),
    )
}

internal fun translationBrowserItems(): List<TranslationBrowserItem> {
    val gguf = TranslationCatalog.models.groupBy { it.family + ":" + it.label.substringBefore(" · ") }
        .map { (key, variants) -> TranslationBrowserItem(key, variants.first().label.substringBefore(" · "), variants.sortedBy { it.bytes }) }
        .sortedWith(compareBy<TranslationBrowserItem> { if (it.variants.first().family == "index-translate") 0 else 1 }.thenBy { it.title })
    val direct = MarianPackage.pairs.map { pair ->
        TranslationBrowserItem(pair.id, "Marian / OPUS-MT", pair = pair)
    }
    val pivot = MarianCascade.routes.map { route ->
        TranslationBrowserItem(route.id, "Marian / OPUS-MT", route = route)
    }
    return gguf + direct + pivot
}

internal fun preferredTranslationVariant(variants: List<TranslationModelSpec>, installedIds: Set<String>, activeId: String): TranslationModelSpec =
    variants.firstOrNull { it.id == activeId && it.id in installedIds }
        ?: variants.firstOrNull { it.id in installedIds }
        ?: variants.firstOrNull { it.quantization == "Q4_K_M" }
        ?: variants.minBy { it.bytes }

/** A part budgets the entire atomic pair; count that private installation once per pair. */
internal fun translationInstallationBytes(parts: List<MarianPackage.Part>): Long =
    parts.distinctBy { it.pairId }.sumOf { it.installedBytes }

/** Cheap UI availability check; full pinned integrity is rechecked on selection and inference. */
internal fun translationFilesPresent(pair: MarianPackage.Pair, directory: File): Boolean =
    directory.isDirectory && pair.parts.all { part -> File(directory, part.fileName).let { it.isFile && it.length() == part.bytes } }

internal data class TranslationDownloadState(
    val stage: DownloadStage?, val completed: Int, val total: Int,
    val record: FileDownload?, val failure: FileDownload?,
) {
    val active: Boolean get() = stage in setOf(DownloadStage.WAITING, DownloadStage.TRANSFERRING, DownloadStage.VERIFYING, DownloadStage.INSTALLING)
}

/** A multi-file model is downloaded only when every required part is present. */
internal fun translationDownloadState(required: Set<String>, records: List<FileDownload>, installedParts: Set<String> = emptySet()): TranslationDownloadState {
    fun rank(row: FileDownload): Int = when {
        row.active -> 0
        row.installed -> 1
        row.complete -> 2
        row.paused -> 3
        else -> 4
    }
    val rows = records.filter { it.modelId in required && it.modelId !in installedParts }
        .groupBy { it.modelId }.mapValues { (_, copies) -> copies.minWith(compareBy<FileDownload>(::rank).thenBy { it.id }) }
    val completed = required.count { it in installedParts || rows[it]?.complete == true }
    val pending = rows.values.filter { !it.complete }
    val active = pending.filter { it.active }.minWithOrNull(compareBy<FileDownload> { it.queuePriority() }.thenBy { it.id })
    val failed = pending.firstOrNull { it.failed }
    val paused = pending.firstOrNull { it.paused }
    val stage = when {
        active != null -> active.displayStage()
        failed != null -> DownloadStage.FAILED
        paused != null -> DownloadStage.PAUSED
        required.isNotEmpty() && completed == required.size -> DownloadStage.READY
        else -> null
    }
    return TranslationDownloadState(stage, completed, required.size, active ?: failed ?: paused ?: rows.values.firstOrNull(), failed)
}
