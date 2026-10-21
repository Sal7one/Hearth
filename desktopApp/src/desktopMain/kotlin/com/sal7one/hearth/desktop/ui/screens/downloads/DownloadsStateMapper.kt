package com.sal7one.hearth.desktop.ui.screens.downloads

import com.sal7one.hearth.desktop.ModelTransfer
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes

/**
 * Turns catalog + installation state + the single in-flight transfer into four
 * buckets. A transfer is shown even when the library has not refreshed yet, so a
 * download started from the library is visible here immediately.
 */
internal fun downloadsState(
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    transfer: ModelTransfer?,
    freeBytes: Long,
): DownloadsState {
    val activeId = transfer?.id

    val items = catalog.map { card ->
        val status = installations[card.id]
        val active = card.id == activeId && transfer != null
        val bucket = when {
            active -> DownloadBucket.DOWNLOADING
            status?.ready == true -> DownloadBucket.INSTALLED
            status?.issue != null -> DownloadBucket.BROKEN
            else -> DownloadBucket.AVAILABLE
        }
        DownloadItem(
            id = card.id,
            name = card.name,
            stageLabel = stageLabel(card.stage),
            sizeLabel = formatBytes(card.bytes),
            detail = when (bucket) {
                DownloadBucket.DOWNLOADING -> if (transfer?.checking == true) {
                    DownloadsCopy.VERIFYING
                } else if (transfer != null && transfer.total > 0) {
                    "${formatBytes(transfer.downloaded)} of ${formatBytes(transfer.total)}"
                } else {
                    DownloadsCopy.STARTING
                }
                DownloadBucket.INSTALLED -> "Verified on this Mac"
                DownloadBucket.BROKEN -> status?.issue ?: "Failed verification"
                DownloadBucket.AVAILABLE -> "Ready to download"
            },
            bucket = bucket,
            progress = if (active && transfer != null && transfer.total > 0) {
                (transfer.downloaded.toFloat() / transfer.total).coerceIn(0f, 1f)
            } else null,
            issue = status?.issue,
        )
    }

    val installed = items.filter { it.bucket == DownloadBucket.INSTALLED }
    return DownloadsState(
        items = items,
        installedCount = installed.size,
        totalCount = items.size,
        activeCount = items.count { it.bucket == DownloadBucket.DOWNLOADING },
        storageLabel = formatBytes(installed.sumOf { item -> catalog.first { it.id == item.id }.bytes }),
        freeDiskLabel = if (freeBytes > 0) formatBytes(freeBytes) else "—",
    )
}
