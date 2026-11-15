package com.sal7one.transiber.ocr

import com.sal7one.hearth.core.vision.MangaArtifacts

/** Each mode selects a complete, pinned page-reading bundle. */
internal enum class MangaPageMode(val profileId: String) {
    FAST(MangaArtifacts.lineProfileId),
    COMPACT("manga-page"),
    KOHARU("manga-koharu-page"),
    CLEANUP("manga-koharu-clean-page"),
}

internal val OcrProfile.mangaPageMode: MangaPageMode? get() = MangaPageMode.entries.firstOrNull { it.profileId == id }

internal fun OcrSelection.withMangaPageMode(mode: MangaPageMode) = withProfile(mode.profileId)
