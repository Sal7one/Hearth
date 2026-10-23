package com.sal7one.hearth.desktop.ui.screens.files

/** One folder Hearth owns. The path is real; the size is only shown when known. */
internal data class LocationEntry(
    val id: String,
    val title: String,
    val detail: String,
    val pathLabel: String,
    val absolutePath: String,
    val sizeLabel: String? = null,
)

internal data class FilesState(
    val locations: List<LocationEntry> = emptyList(),
    val modelsLabel: String = "0 MB",
    val catalogLabel: String = "0 MB",
    val storageFraction: Float = 0f,
    val freeLabel: String = "—",
    val dataDirectory: String = "",
)
