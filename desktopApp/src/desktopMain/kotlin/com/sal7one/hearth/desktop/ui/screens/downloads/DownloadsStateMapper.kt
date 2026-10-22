package com.sal7one.hearth.desktop.ui.screens.downloads

import com.sal7one.hearth.desktop.ModelTransfer
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes

/**
 * Turns catalog + installation state + the per-model transfers into four
 * buckets. A transfer is shown even when the library has not refreshed yet, so a
 * download started from the library is visible here immediately.
 */
internal fun downloadsState(
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    transfers: Collection<ModelTransfer>,
    freeBytes: Long,
    issues: Map<String, String> = emptyMap(),
): DownloadsState {
    val byId = transfers.associateBy { it.id }

    val items = catalog.map { card ->
        val status = installations[card.id]
        val issue = issues[card.id] ?: status?.issue
        val transfer = byId[card.id]
        val active = transfer != null
        val bucket = when {
            active -> DownloadBucket.DOWNLOADING
            status?.ready == true -> DownloadBucket.INSTALLED
            issue != null -> DownloadBucket.BROKEN
            else -> DownloadBucket.AVAILABLE
        }
        DownloadItem(
            id = card.id,
            name = card.name,
            stageLabel = stageLabel(card.stage),
            sizeLabel = formatBytes(card.bytes),
            detail = when (bucket) {
                DownloadBucket.DOWNLOADING -> requireNotNull(transfer).let { current ->
                    current.label + when {
                        current.queued -> " · Waiting for a slot"
                        current.total > 0 -> " · ${formatBytes(current.downloaded)} of ${formatBytes(current.total)}" +
                            current.component.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        else -> "…"
                    }
                }
                DownloadBucket.INSTALLED -> "Verified on this Mac"
                DownloadBucket.BROKEN -> issue ?: "Failed verification"
                DownloadBucket.AVAILABLE -> "Ready to download"
            },
            bucket = bucket,
            progress = if (transfer != null && !transfer.queued && transfer.total > 0) {
                (transfer.downloaded.toFloat() / transfer.total).coerceIn(0f, 1f)
            } else null,
            issue = issue,
            cancellable = transfer?.cancelling != true,
        )
    }

    val installed = items.filter { it.bucket == DownloadBucket.INSTALLED }
    return DownloadsState(
        items = items,
        installedCount = installed.size,
        totalCount = items.size,
        activeCount = items.count { it.bucket == DownloadBucket.DOWNLOADING },
        storageLabel = formatBytes(installed.sumOf { item -> catalog.first { it.id == item.id }.installedBytes }),
        freeDiskLabel = if (freeBytes > 0) formatBytes(freeBytes) else "—",
    )
}
