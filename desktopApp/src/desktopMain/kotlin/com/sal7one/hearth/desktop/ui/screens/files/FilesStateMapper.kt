package com.sal7one.hearth.desktop.ui.screens.files

import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes
import java.nio.file.Path

/**
 * Builds the storage page from the real data directory and the verified model set.
 * Folders Hearth does not own are not listed, and a size is only shown when it has
 * actually been counted.
 */
internal fun filesState(
    dataDirectory: Path,
    catalog: List<DesktopModelCard>,
    installations: Map<String, DesktopModelStatus>,
    freeBytes: Long,
): FilesState {
    val installed = catalog.filter { installations[it.id]?.ready == true }
    val installedBytes = installed.sumOf { it.bytes }
    val catalogBytes = catalog.sumOf { it.bytes }
    val modelsRoot = dataDirectory.resolve("models")

    return FilesState(
        locations = listOf(
            LocationEntry(
                id = "data",
                title = FilesCopy.DATA,
                detail = FilesCopy.DATA_DETAIL,
                pathLabel = dataDirectory.toString().replace(System.getProperty("user.home"), "~"),
                absolutePath = dataDirectory.toString(),
            ),
            LocationEntry(
                id = "models",
                title = FilesCopy.MODELS,
                detail = FilesCopy.MODELS_DETAIL,
                pathLabel = modelsRoot.toString().replace(System.getProperty("user.home"), "~"),
                absolutePath = modelsRoot.toString(),
                sizeLabel = formatBytes(installedBytes),
            ),
        ),
        modelsLabel = formatBytes(installedBytes),
        catalogLabel = formatBytes(catalogBytes),
        storageFraction = if (catalogBytes > 0) installedBytes.toFloat() / catalogBytes else 0f,
        freeLabel = if (freeBytes > 0) formatBytes(freeBytes) else "—",
        dataDirectory = dataDirectory.toString(),
    )
}
