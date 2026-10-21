package com.sal7one.hearth.desktop.ui.screens.models

import com.sal7one.hearth.core.PipelineStage

/** What a catalog entry currently is to the operator. */
internal enum class ModelStatus { INSTALLED, AVAILABLE, BROKEN }

internal data class ModelRow(
    val id: String,
    val name: String,
    val stage: PipelineStage,
    val sizeLabel: String,
    val metaLabel: String,
    val status: ModelStatus,
    val inUse: Boolean,
    val issue: String? = null,
) {
    val actionLabel: String
        get() = when {
            inUse -> "In use"
            status == ModelStatus.INSTALLED -> "Use"
            status == ModelStatus.BROKEN -> "Repair"
            else -> "Download"
        }

    val actionEnabled: Boolean get() = !inUse
}

internal data class ModelGroup(
    val stage: PipelineStage,
    val title: String,
    val detail: String,
    val rows: List<ModelRow>,
) {
    val installedCount: Int get() = rows.count { it.status != ModelStatus.AVAILABLE }
}

internal data class ModelsFilter(
    val query: String = "",
    val stage: PipelineStage? = null,
    val onlyInstalled: Boolean = false,
)

/** A real transfer, already resolved to display strings by the mapper. */
internal data class TransferView(
    val modelName: String,
    val fraction: Float,
    val detail: String,
    val checking: Boolean,
)

/**
 * The library as the page renders it: grouped by stage, filtered, with real
 * installation state and real byte counts.
 */
internal data class ModelsState(
    val groups: List<ModelGroup> = emptyList(),
    val filter: ModelsFilter = ModelsFilter(),
    val installedCount: Int = 0,
    val totalCount: Int = 0,
    val storageLabel: String = "0 MB",
    val catalogLabel: String = "0 MB",
    val storageFraction: Float = 0f,
    val freeDiskLabel: String = "—",
    val transfer: TransferView? = null,
    val refreshing: Boolean = false,
    val issues: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.all { it.rows.isEmpty() }
    val isEmptyBecauseOfFilter: Boolean get() = isEmpty && (filter.query.isNotBlank() || filter.onlyInstalled || filter.stage != null)
}
