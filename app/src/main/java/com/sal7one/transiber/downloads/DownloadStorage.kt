package com.sal7one.transiber.downloads

import android.content.Context
import android.os.StatFs

internal object DownloadStorage {
    fun available(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes
    fun requireSpace(context: Context, asset: DownloadAsset?, downloaded: Long, customFolder: Boolean) {
        if (asset == null) return
        val budget = DownloadBudget.estimate(asset.bytes, asset.installedBytes, downloaded.coerceIn(0,asset.bytes))
        // Default public Downloads and filesDir share device storage. SAF may be another volume;
        // its provider need not expose capacity, so retain real write errors for that destination.
        val required = if (customFolder) budget.privateVolumeRequired else budget.sameVolumeRequired
        val free = available(context)
        check(free >= required) { "Not enough device storage: ${required / 1_048_576} MiB required, ${free / 1_048_576} MiB available. Downloaded originals and installed models are separate copies." }
    }
}
