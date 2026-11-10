package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.vision.MangaArtifacts

internal val readingModelIds = setOf(MangaArtifacts.lineProfileId, "manga-page", "manga-koharu-page", "manga-koharu-clean-page")
internal fun selectedReadingModel(ui: Map<String, String>): String =
    ui["reading-model-id"]?.takeIf { it in readingModelIds }
        ?: if (ui["reading-model-path"].isNullOrBlank()) MangaArtifacts.lineProfileId else "manga-page"

internal fun readingModelLanguages(profileId: String?): Set<String> =
    if (profileId == MangaArtifacts.lineProfileId) MangaArtifacts.lineLanguages else setOf("ja")

internal fun readingModelSettings(id: String, path: String): Map<String, String> =
    mapOf("reading-model-id" to id, "reading-model-path" to path)
