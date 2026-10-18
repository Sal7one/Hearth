package com.sal7one.transiber.downloads

import android.app.DownloadManager

/** Transfer percentages describe network bytes only, never verification or installation. */
internal enum class DownloadStage { WAITING, TRANSFERRING, VERIFYING, INSTALLING, PAUSED, FAILED, READY, INSTALLED }

internal fun FileDownload.displayStage(): DownloadStage = when (phase) {
    "Installed" -> DownloadStage.INSTALLED
    "Verifying" -> DownloadStage.VERIFYING
    "Installing" -> DownloadStage.INSTALLING
    "Paused" -> DownloadStage.PAUSED
    "Failed", "Interrupted" -> DownloadStage.FAILED
    "Complete" -> DownloadStage.READY
    "Waiting" -> DownloadStage.WAITING
    "Downloading" -> DownloadStage.TRANSFERRING
    else -> when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> DownloadStage.READY
        DownloadManager.STATUS_FAILED -> DownloadStage.FAILED
        DownloadManager.STATUS_PAUSED -> DownloadStage.PAUSED
        DownloadManager.STATUS_PENDING -> DownloadStage.WAITING
        else -> DownloadStage.TRANSFERRING
    }
}

internal fun FileDownload.transferFraction(): Float? =
    if (displayStage() == DownloadStage.TRANSFERRING && total > 0)
        (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

internal fun FileDownload.queuePriority(): Int = when (displayStage()) {
    DownloadStage.WAITING, DownloadStage.TRANSFERRING, DownloadStage.VERIFYING, DownloadStage.INSTALLING -> 0
    DownloadStage.FAILED, DownloadStage.PAUSED -> 1
    DownloadStage.READY -> 2
    DownloadStage.INSTALLED -> 3
}
