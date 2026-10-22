package com.sal7one.hearth.desktop.ui.screens.models

import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ModelTransfer
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.selection
import java.util.Locale

/**
 * Builds the library view from the real catalog and installation map. Nothing is
 * assumed: a card is "installed" only when the library verification says so, and a
 * card is "in use" only when the saved configuration points at it.
 */
internal fun modelsState(
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    config: PipelineConfig,
    transfers: Collection<ModelTransfer>,
    filter: ModelsFilter,
    freeBytes: Long,
    verifiedImports: Map<String, EngineSelection> = emptyMap(),
    issues: Map<String, String> = emptyMap(),
): ModelsState {
    val verified = installations.toMutableMap()
    verifiedImports.forEach { (id, route) ->
        if (catalog.any { it.id == id && it.engineId == route.engineId } && route.modelPath.isNotBlank()) {
            verified[id] = DesktopModelStatus(true, java.nio.file.Path.of(route.modelPath))
        }
    }
    issues.forEach { (id, issue) -> verified[id] = (verified[id] ?: DesktopModelStatus(false, null)).copy(issue = issue) }
    val rows = catalog.map { card -> modelRow(card, verified[card.id], config).copy(activity = transfers.firstOrNull { it.id == card.id }?.label) }

    val filtered = rows.filter { row ->
        val card = catalog.first { it.id == row.id }
        val matchesQuery = filter.query.isBlank() || listOf(card.name, card.family, card.stage.name)
            .any { it.contains(filter.query, ignoreCase = true) } ||
            card.languages.any { it.contains(filter.query, ignoreCase = true) }
        val matchesStage = filter.stage == null || card.stage == filter.stage
        val matchesInstalled = !filter.onlyInstalled || row.status != ModelStatus.AVAILABLE
        matchesQuery && matchesStage && matchesInstalled
    }

    val groups = listOf(PipelineStage.STT, PipelineStage.TRANSLATION, PipelineStage.TTS).map { stage ->
        ModelGroup(
            stage = stage,
            title = ModelsCopy.groupTitle(stage),
            detail = ModelsCopy.groupDetail(stage),
            rows = filtered.filter { it.stage == stage },
        )
    }

    val installedCards = catalog.filter { verified[it.id]?.ready == true }
    val installedBytes = installedCards.sumOf { it.installedBytes }
    val catalogBytes = catalog.sumOf { it.installedBytes }
    val activeTransfers = transfers.map { current ->
        val card = catalog.firstOrNull { it.id == current.id }
        TransferView(
            modelName = card?.name ?: current.id,
            fraction = if (current.total > 0) (current.downloaded.toFloat() / current.total).coerceIn(0f, 1f) else 0f,
            detail = "${current.label} · " + when {
                current.queued -> "Waiting for a slot"
                current.total > 0 -> "${formatBytes(current.downloaded)} of ${formatBytes(current.total)}" + current.component.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                else -> "Starting…"
            },
            checking = current.checking,
        )
    }

    return ModelsState(
        groups = groups,
        filter = filter,
        installedCount = installedCards.size,
        totalCount = catalog.size,
        storageLabel = formatBytes(installedBytes),
        catalogLabel = formatBytes(catalogBytes),
        storageFraction = if (catalogBytes > 0) installedBytes.toFloat() / catalogBytes else 0f,
        freeDiskLabel = if (freeBytes > 0) formatBytes(freeBytes) else "—",
        transfers = activeTransfers,
        refreshing = false,
        issues = verified.values.mapNotNull { it.issue }.distinct(),
    )
}

private fun modelRow(card: DesktopModelCard, status: DesktopModelStatus?, config: PipelineConfig): ModelRow {
    val chosen = config.selection(card.stage)
    val inUse = status?.ready == true && chosen?.modelId == card.id && chosen.engineId == card.engineId &&
        chosen.modelPath == status.path?.toString()
    val modelStatus = when {
        status?.ready == true -> ModelStatus.INSTALLED
        status != null && status.issue != null -> ModelStatus.BROKEN
        else -> ModelStatus.AVAILABLE
    }
    return ModelRow(
        id = card.id,
        name = card.name,
        stage = card.stage,
        sizeLabel = formatBytes(card.installedBytes),
        metaLabel = languageLabel(card.languages),
        status = modelStatus,
        inUse = inUse,
        issue = status?.issue,
    )
}

/** 1_674_000_000 → "1.55 GB"; whole megabytes below a gigabyte. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    if (bytes < 1000) return "$bytes B"
    if (bytes < 1_000_000) return String.format(Locale.ENGLISH, "%.0f KB", bytes / 1000.0)
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1) return String.format(Locale.ENGLISH, "%.2f GB", gb)
    val mb = bytes / 1_000_000.0
    if (mb < 10) return String.format(Locale.ENGLISH, "%.1f MB", mb)
    return String.format(Locale.ENGLISH, "%.0f MB", mb)
}

/** "99 languages" / "Multiple languages" / "Arabic, English". */
internal fun languageLabel(languages: Set<String>): String {
    if (languages.isEmpty()) return "Languages not listed"
    if ("multilingual" in languages) return "Multiple languages"
    val names = languages.take(3).map { code ->
        runCatching { Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: code.uppercase()
    }
    val suffix = if (languages.size > 3) " +${languages.size - 3}" else ""
    return names.joinToString(", ") + suffix
}

/** "1 installed" vs "2 installed" — used in the group header chip. */
internal fun installedLabel(count: Int): String = if (count == 1) "1 installed" else "$count installed"
