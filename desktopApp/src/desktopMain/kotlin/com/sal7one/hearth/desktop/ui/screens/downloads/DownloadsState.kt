package com.sal7one.hearth.desktop.ui.screens.downloads

import com.sal7one.hearth.core.PipelineStage

internal enum class DownloadBucket { DOWNLOADING, AVAILABLE, INSTALLED, BROKEN }

internal data class DownloadItem(
    val id: String,
    val name: String,
    val stageLabel: String,
    val sizeLabel: String,
    val detail: String,
    val bucket: DownloadBucket,
    val progress: Float? = null,
    val issue: String? = null,
)

internal data class DownloadsState(
    val items: List<DownloadItem> = emptyList(),
    val installedCount: Int = 0,
    val totalCount: Int = 0,
    val activeCount: Int = 0,
    val storageLabel: String = "0 MB",
    val freeDiskLabel: String = "—",
    val refreshing: Boolean = false,
) {
    val downloading: List<DownloadItem> get() = items.filter { it.bucket == DownloadBucket.DOWNLOADING }
    val available: List<DownloadItem> get() = items.filter { it.bucket == DownloadBucket.AVAILABLE }
    val installed: List<DownloadItem> get() = items.filter { it.bucket == DownloadBucket.INSTALLED }
    val broken: List<DownloadItem> get() = items.filter { it.bucket == DownloadBucket.BROKEN }
}

internal fun stageLabel(stage: PipelineStage): String = when (stage) {
    PipelineStage.STT -> "Speech"
    PipelineStage.TRANSLATION -> "Translation"
    PipelineStage.TTS -> "Voice"
}
